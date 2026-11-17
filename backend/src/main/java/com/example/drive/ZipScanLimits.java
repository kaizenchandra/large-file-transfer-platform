package com.example.drive;

import java.io.EOFException;
import java.io.IOException;
import java.io.PushbackInputStream;
import java.util.zip.ZipInputStream;

/**
 * Independent budget for ZIP local entries. Never extracts names or contents onto the filesystem.
 */
final class ZipScanLimits {
    private ZipScanLimits() {
    }

    static boolean nested(byte[] prefix) {
        return (prefix.length >= 4 && prefix[0] == 'P' && prefix[1] == 'K' && (prefix[2] == 3 || prefix[2] == 5 || prefix[2] == 7))
                || (prefix.length >= 2 && (prefix[0] & 255) == 0x1f && (prefix[1] & 255) == 0x8b)
                || (prefix.length >= 4 && prefix[0] == 'R' && prefix[1] == 'a' && prefix[2] == 'r' && prefix[3] == '!')
                || (prefix.length >= 6 && prefix[0] == '7' && prefix[1] == 'z' && prefix[2] == (byte) 0xbc && prefix[3] == (byte) 0xaf && prefix[4] == 0x27 && prefix[5] == 0x1c)
                || (prefix.length >= 3 && prefix[0] == 'B' && prefix[1] == 'Z' && prefix[2] == 'h')
                || (prefix.length >= 6 && prefix[0] == (byte) 0xfd && prefix[1] == '7' && prefix[2] == 'z' && prefix[3] == 'X' && prefix[4] == 'Z' && prefix[5] == 0)
                || (prefix.length >= 262 && prefix[257] == 'u' && prefix[258] == 's' && prefix[259] == 't' && prefix[260] == 'a' && prefix[261] == 'r');
    }

    static boolean blocked(ObjectStore store, String key, long budget) throws IOException {
        try (var source = store.open(key); var stream = new PushbackInputStream(source, 512)) {
            byte[] magic = stream.readNBytes(512);
            boolean localZip = magic.length >= 4 && magic[0] == 'P' && magic[1] == 'K' && magic[2] == 3 && magic[3] == 4;
            if (!localZip) {
                boolean emptyZip = magic.length >= 4 && magic[0] == 'P' && magic[1] == 'K' && magic[2] == 5 && magic[3] == 6;
                return !emptyZip && nested(magic);
            }
            stream.unread(magic);
            try (var zip = new ZipInputStream(stream)) {
                long total = 0;
                int entries = 0;
                byte[] buffer = new byte[128 * 1024];
                while (zip.getNextEntry() != null) {
                    if (++entries > 1000) return true;
                    byte[] prefix = zip.readNBytes(512);
                    total += prefix.length;
                    if (total > budget || nested(prefix)) return true;
                    int read;
                    while ((read = zip.read(buffer)) != -1) {
                        total += read;
                        if (total > budget) return true;
                    }
                    zip.closeEntry();
                }
                return entries == 0; // A local-header prefix without even one valid entry is not approved.
            } catch (java.util.zip.ZipException | EOFException | IllegalArgumentException malformed) {
                return true; // A definitively malformed/encrypted ZIP is blocked, not retried as a scanner outage.
            }
        }
    }
}
