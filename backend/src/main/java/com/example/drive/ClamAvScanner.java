package com.example.drive;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.health.contributor.Health;
import org.springframework.stereotype.Component;

import java.io.*;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

/**
 * Bounded ClamD INSTREAM client. Only an exact clean response permits publication.
 */
@Component
public class ClamAvScanner implements MalwareScanner, AutoCloseable {
    private final String host;
    private final int port, timeout;
    private final ScheduledExecutorService deadlines = Executors.newSingleThreadScheduledExecutor(
            Thread.ofPlatform().daemon().name("scan-deadlines").factory());
    private boolean healthRequired = true;

    public ClamAvScanner(@Value("${drive.scanner.host:localhost}") String host,
                         @Value("${drive.scanner.port:3310}") int port,
                         @Value("${drive.scanner.timeout-ms:30000}") int timeout) {
        if (host.isBlank() || port < 1 || port > 65535 || timeout < 1)
            throw new IllegalArgumentException("Invalid scanner bounds");
        this.host = host;
        this.port = port;
        this.timeout = timeout;
    }

    @org.springframework.beans.factory.annotation.Autowired
    void configureHealth(@Value("${drive.local:false}") boolean local, @Value("${drive.scan-mode:AUTO}") String mode) {
        healthRequired = mode.equalsIgnoreCase("REQUIRED") || (mode.equalsIgnoreCase("AUTO") && !local);
    }

    private String reply(InputStream in) throws IOException {
        var result = new ByteArrayOutputStream();
        for (int count = 0; count < 1024; count++) {
            int b = in.read();
            if (b < 0) throw new EOFException("Incomplete scanner reply");
            if (b == 0) return result.toString(StandardCharsets.US_ASCII);
            if (b < 32 || b > 126) throw new IOException("Malformed scanner reply");
            result.write(b);
        }
        throw new IOException("Scanner reply exceeds bound");
    }

    private String command(String command) throws IOException {
        int limit = Math.min(timeout, 2000);
        try (var socket = new Socket()) {
            var expiry = deadlines.schedule(() -> {
                try {
                    socket.close();
                } catch (IOException ignored) {
                }
            }, limit, TimeUnit.MILLISECONDS);
            try {
                socket.connect(new InetSocketAddress(host, port), limit);
                socket.setSoTimeout(limit);
                socket.getOutputStream().write(("z" + command + "\0").getBytes(StandardCharsets.US_ASCII));
                return reply(socket.getInputStream());
            } finally {
                expiry.cancel(false);
            }
        }
    }

    @Override
    public Verdict scan(InputStream content) throws IOException {
        String engine = command("VERSION");
        if (!engine.startsWith("ClamAV ")) throw new IOException("Scanner identity unavailable");
        try (var socket = new Socket()) {
            var expiry = deadlines.schedule(() -> {
                try {
                    socket.close();
                } catch (IOException ignored) {
                }
                try {
                    content.close();
                } catch (IOException ignored) {
                }
            }, timeout, TimeUnit.MILLISECONDS);
            try {
                socket.connect(new InetSocketAddress(host, port), timeout);
                socket.setSoTimeout(timeout);
                var out = new DataOutputStream(new BufferedOutputStream(socket.getOutputStream()));
                out.write("zINSTREAM\0".getBytes(StandardCharsets.US_ASCII));
                byte[] buffer = new byte[128 * 1024];
                int read;
                while ((read = content.read(buffer)) != -1) {
                    if (read == 0) throw new IOException("Inspection source made no progress");
                    out.writeInt(read);
                    out.write(buffer, 0, read);
                }
                out.writeInt(0);
                out.flush();
                String result = reply(socket.getInputStream());
                if (result.equals("stream: OK")) return new Verdict(false, engine);
                if (result.startsWith("stream: ") && result.length() > "stream:  FOUND".length() && result.endsWith(" FOUND"))
                    return new Verdict(true, engine);
                throw new IOException("Scanner did not provide a definitive verdict");
            } finally {
                expiry.cancel(false);
            }
        }
    }

    public Health health() {
        if (!healthRequired) return Health.up().withDetail("mode", "local-bypass").build();
        try {
            return command("PING").equals("PONG") ? Health.up().build() : Health.down().build();
        } catch (IOException e) {
            return Health.down().build();
        }
    }

    @Override
    public void close() {
        deadlines.shutdownNow();
    }
}
