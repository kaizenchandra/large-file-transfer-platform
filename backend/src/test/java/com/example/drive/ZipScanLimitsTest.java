package com.example.drive;

import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class ZipScanLimitsTest {
    byte[] archive(byte[] content) throws IOException {
        var bytes = new ByteArrayOutputStream();
        try (var zip = new ZipOutputStream(bytes)) {
            zip.putNextEntry(new ZipEntry("content"));
            zip.write(content);
            zip.closeEntry();
        }
        return bytes.toByteArray();
    }

    ObjectStore containing(byte[] bytes) {
        var store = mock(ObjectStore.class);
        when(store.open("key")).thenAnswer(call -> new ByteArrayInputStream(bytes));
        return store;
    }

    @Test
    void independentBudgetRejectsExpandedAndNestedContent() throws Exception {
        assertTrue(ZipScanLimits.blocked(containing(archive(new byte[2048])), "key", 1024));
        assertFalse(ZipScanLimits.blocked(containing(archive(new byte[512])), "key", 1024));
        assertTrue(ZipScanLimits.blocked(containing(archive(archive(new byte[1]))), "key", 1024));
        assertTrue(ZipScanLimits.blocked(containing(archive(new byte[]{0x1f, (byte) 0x8b, 0, 0})), "key", 1024));
        assertFalse(ZipScanLimits.blocked(containing("plain content".getBytes()), "key", 1024));
    }
}
