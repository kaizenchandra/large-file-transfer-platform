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

class ArchivePolicyTest {
    byte[] zip(byte[] content) throws IOException {
        var output = new ByteArrayOutputStream();
        try (var archive = new ZipOutputStream(output)) {
            archive.putNextEntry(new ZipEntry("member"));
            archive.write(content);
            archive.closeEntry();
        }
        return output.toByteArray();
    }

    boolean blocked(byte[] content, long budget) throws IOException {
        var objects = mock(ObjectStore.class);
        when(objects.open("object")).thenAnswer(call -> new ByteArrayInputStream(content));
        return ZipScanLimits.blocked(objects, "object", budget);
    }

    @Test
    void plainAndBoundedZipPassPreflightButExpansionAndNestedContainersAreBlocked() throws Exception {
        assertFalse(blocked("plain content".getBytes(), 1024));
        assertFalse(blocked(zip("small content".getBytes()), 1024));
        assertTrue(blocked(zip(new byte[1024 * 1024]), 65536));
        assertTrue(blocked(zip(zip("nested".getBytes())), 65536));
        assertTrue(blocked(zip(new byte[]{0x1f, (byte) 0x8b, 8, 0, 0, 0}), 1024));
    }

    @Test
    void UnsupportedContainersAreBlockedRatherThanAssumedCompletelyScanned() throws Exception {
        assertTrue(blocked(new byte[]{0x1f, (byte) 0x8b, 8, 0, 0, 0}, 1024));
        assertTrue(blocked(new byte[]{'R', 'a', 'r', '!', 0, 0}, 1024));
        assertTrue(blocked(new byte[]{'7', 'z', (byte) 0xbc, (byte) 0xaf, 0x27, 0x1c}, 1024));
        var tar = new byte[512];
        System.arraycopy("ustar".getBytes(), 0, tar, 257, 5);
        assertTrue(blocked(tar, 1024));
    }

    @Test
    void malformedZipHeaderIsBlocked() throws Exception {
        assertTrue(blocked(new byte[]{'P', 'K', 3, 4, 1, 2, 3, 4}, 1024));
    }
}
