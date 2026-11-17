package com.example.drive;

import org.junit.jupiter.api.Test;

import java.io.*;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.junit.jupiter.api.Assertions.*;

class ScanDeadlineTest {
    @Test
    void versionReplyCannotExtendDeadlineByTricklingBytes() throws Exception {
        try (var endpoint = new Endpoint(true); var scanner = endpoint.scanner()) {
            assertTimeoutPreemptively(Duration.ofMillis(800), () -> assertThrows(IOException.class, () -> scanner.scan(new ByteArrayInputStream(new byte[0]))));
        }
    }

    @Test
    void streamingDeadlineClosesAStalledSource() throws Exception {
        var closed = new AtomicBoolean();
        var released = new CountDownLatch(1);
        var source = new InputStream() {
            @Override
            public int read() throws IOException {
                try {
                    if (!released.await(3, TimeUnit.SECONDS)) throw new IOException("Source was not interrupted");
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    throw new IOException(e);
                }
                return -1;
            }

            @Override
            public void close() {
                closed.set(true);
                released.countDown();
            }
        };
        try (var endpoint = new Endpoint(false); var scanner = endpoint.scanner()) {
            assertTimeoutPreemptively(Duration.ofSeconds(2), () -> assertThrows(IOException.class, () -> scanner.scan(source)));
            assertTrue(closed.get());
        } finally {
            source.close();
        }
    }

    static class Endpoint implements AutoCloseable {
        final ServerSocket server = new ServerSocket(0, 10, InetAddress.getLoopbackAddress());
        final ExecutorService workers = Executors.newVirtualThreadPerTaskExecutor();

        Endpoint(boolean dripVersion) throws IOException {
            workers.submit(() -> {
                while (!server.isClosed()) try {
                    var socket = server.accept();
                    workers.submit(() -> serve(socket, dripVersion));
                } catch (IOException closed) {
                    break;
                }
            });
        }

        void serve(Socket socket, boolean drip) {
            try (socket) {
                var input = new DataInputStream(socket.getInputStream());
                var command = new ByteArrayOutputStream();
                int b;
                while ((b = input.read()) > 0) command.write(b);
                if (command.toString(StandardCharsets.US_ASCII).equals("zVERSION")) {
                    byte[] version = ("ClamAV " + "x".repeat(64) + "\0").getBytes(StandardCharsets.US_ASCII);
                    if (drip) for (byte value : version) {
                        socket.getOutputStream().write(value);
                        Thread.sleep(50);
                    }
                    else socket.getOutputStream().write(version);
                } else {
                    while (input.read() != -1) { /* wait for deadline to close the connection */ }
                }
            } catch (IOException | InterruptedException expectedDisconnect) {
            }
        }

        ClamAvScanner scanner() {
            return new ClamAvScanner("127.0.0.1", server.getLocalPort(), 100);
        }

        @Override
        public void close() throws IOException {
            server.close();
            workers.shutdownNow();
        }
    }
}
