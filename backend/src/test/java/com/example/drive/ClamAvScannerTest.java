package com.example.drive;

import org.junit.jupiter.api.Test;

import java.io.*;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Random;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;

class ClamAvScannerTest {
    @Test
    void streamsBoundedChunksAndAcceptsOnlyCleanVerdict() throws Exception {
        byte[] bytes = new byte[300000];
        new Random(7).nextBytes(bytes);
        try (var daemon = new Daemon("stream: OK\0", 0); var scanner = daemon.scanner(2000)) {
            assertFalse(scanner.scan(new ByteArrayInputStream(bytes)).blocked());
            assertArrayEquals(bytes, daemon.received.get());
            assertFalse(scanner.scan(new ByteArrayInputStream(new byte[0])).blocked());
            assertEquals(0, daemon.received.get().length);
        }
    }

    @Test
    void rejectsDetectionsAndUncertainReplies() throws Exception {
        try (var daemon = new Daemon("stream: Test.Detection FOUND\0", 0); var scanner = daemon.scanner(2000)) {
            assertTrue(scanner.scan(new ByteArrayInputStream(new byte[1])).blocked());
        }
        for (String response : List.of("stream: OK", "UNKNOWN COMMAND\0", "INSTREAM size limit exceeded. ERROR\0", "stream: OK additional text\0", "stream: " + "x".repeat(3000) + "\0")) {
            try (var daemon = new Daemon(response, 0); var scanner = daemon.scanner(2000)) {
                assertThrows(IOException.class, () -> scanner.scan(new ByteArrayInputStream(new byte[1])));
            }
        }
    }

    @Test
    void deadlineClosesStalledStream() throws Exception {
        try (var daemon = new Daemon("stream: OK\0", 2000); var scanner = daemon.scanner(100)) {
            assertTimeoutPreemptively(java.time.Duration.ofSeconds(2), () -> assertThrows(IOException.class, () -> scanner.scan(new ByteArrayInputStream(new byte[1]))));
        }
    }

    @Test
    void productionCannotDisableGateAndOversizeIsRejectedBeforeStorageAccess() {
        var metrics = new io.micrometer.core.instrument.simple.SimpleMeterRegistry();
        var scanner = org.mockito.Mockito.mock(MalwareScanner.class);
        assertThrows(IllegalArgumentException.class, () -> new MalwareGate(scanner, metrics, false, "DISABLED", 100, "v1"));
        var production = new MalwareGate(scanner, metrics, false, "AUTO", 100, "v1");
        assertTrue(production.required());
        assertThrows(org.springframework.web.server.ResponseStatusException.class, () -> production.admit(101));
        assertFalse(new MalwareGate(scanner, metrics, true, "AUTO", 100, "v1").required());
    }

    /**
     * Protocol fixture only; real engine detection is exercised separately in MalwareIntegrationTest.
     */
    static class Daemon implements AutoCloseable {
        final ServerSocket server = new ServerSocket(0, 10, InetAddress.getLoopbackAddress());
        final ExecutorService workers = Executors.newVirtualThreadPerTaskExecutor();
        final AtomicReference<byte[]> received = new AtomicReference<>();
        final String response;
        final long stall;

        Daemon(String response, long stall) throws IOException {
            this.response = response;
            this.stall = stall;
            workers.submit(() -> {
                while (!server.isClosed()) try {
                    Socket socket = server.accept();
                    workers.submit(() -> handle(socket));
                } catch (IOException closed) {
                    break;
                }
            });
        }

        void handle(Socket socket) {
            try (socket) {
                var input = new DataInputStream(socket.getInputStream());
                var command = new ByteArrayOutputStream();
                int b;
                while ((b = input.read()) > 0) command.write(b);
                if (command.toString(StandardCharsets.US_ASCII).equals("zVERSION")) {
                    socket.getOutputStream().write("ClamAV test-engine/test-db\0".getBytes(StandardCharsets.US_ASCII));
                    return;
                }
                if (stall > 0) Thread.sleep(stall);
                var content = new ByteArrayOutputStream();
                int size;
                while ((size = input.readInt()) != 0) {
                    if (size < 0 || size > 128 * 1024) throw new IOException("Invalid chunk shape");
                    byte[] part = input.readNBytes(size);
                    if (part.length != size) throw new EOFException();
                    content.write(part);
                }
                received.set(content.toByteArray());
                socket.getOutputStream().write(response.getBytes(StandardCharsets.US_ASCII));
            } catch (IOException | InterruptedException expectedDisconnect) {
            }
        }

        ClamAvScanner scanner(int timeout) {
            return new ClamAvScanner("127.0.0.1", server.getLocalPort(), timeout);
        }

        public void close() throws IOException {
            server.close();
            workers.shutdownNow();
        }
    }
}
