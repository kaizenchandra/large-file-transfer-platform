package com.example.drive;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import javax.tools.ToolProvider;
import java.net.URLClassLoader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.text.Normalizer;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Compiles the actual standalone client so naming tests cannot drift into a backend-only copy.
 */
class SyncClientRulesTest {
    @TempDir
    Path output;

    @Test
    void conflictNamesFitFilesystemAndPreserveUnicodeBoundaries() throws Exception {
        String cp = System.getProperty("surefire.test.class.path", System.getProperty("java.class.path"));
        assertEquals(0, ToolProvider.getSystemJavaCompiler().run(null, null, null, "-cp", cp, "-d", output.toString(), "sync-client/SyncClient.java"));
        try (var loader = new URLClassLoader(new java.net.URL[]{output.toUri().toURL()}, getClass().getClassLoader())) {
            var method = loader.loadClass("SyncClient").getDeclaredMethod("conflictName", String.class, String.class);
            method.setAccessible(true);
            String suffix = ".conflict-" + UUID.randomUUID();
            for (String name : List.of("x".repeat(255), "é".repeat(120), "文".repeat(80), "😀".repeat(60), "notes.txt")) {
                String conflict = (String) method.invoke(null, name, suffix);
                assertTrue(conflict.endsWith(suffix));
                assertTrue(conflict.getBytes(StandardCharsets.UTF_8).length <= 255);
                assertTrue(Normalizer.isNormalized(conflict, Normalizer.Form.NFC));
                assertEquals(conflict, Rules.name(conflict));
                assertEquals(conflict, new String(conflict.getBytes(StandardCharsets.UTF_8), StandardCharsets.UTF_8));
                assertNotEquals(conflict, method.invoke(null, name, ".conflict-" + UUID.randomUUID()));
            }
        }
    }
}
