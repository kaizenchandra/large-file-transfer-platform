package com.example.drive;

import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Base64;

public final class Rules {
    private Rules() {
    }

    public static void require(boolean ok, int code, String message) {
        if (!ok) throw new ResponseStatusException(HttpStatus.valueOf(code), message);
    }

    public static String name(String value) {
        require(value != null && !value.isBlank() && value.length() <= 255 && !value.equals(".") && !value.equals("..") && value.chars().noneMatch(c -> c < 32 || c == 127 || c == '/' || c == '\\'), 400, "Invalid name");
        require(value.equals(java.text.Normalizer.normalize(value, java.text.Normalizer.Form.NFC)), 400, "Use NFC names");
        return value;
    }

    public static String checksum(String s) {
        try {
            require(s != null && Base64.getDecoder().decode(s).length == 32, 400, "Expected base64 SHA-256");
        } catch (IllegalArgumentException e) {
            require(false, 400, "Expected base64 SHA-256");
        }
        return s;
    }

    public static String hash(byte[] b) {
        try {
            return Base64.getEncoder().encodeToString(MessageDigest.getInstance("SHA-256").digest(b));
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    public static String hash(String s) {
        return hash(s.getBytes(StandardCharsets.UTF_8));
    }

    public static long partSize(long size) {
        require(size >= 0 && size <= 5L * 1024 * 1024 * 1024 * 1024, 400, "Size outside 0 bytes..5 TiB");
        return Math.max(8L * 1024 * 1024, (size + 9999) / 10000);
    }

    public static int partCount(long size, long partSize) {
        return (int) Math.max(1, (size + partSize - 1) / partSize);
    }

    public static long cursor(String c) {
        try {
            long n = Long.parseLong(c);
            require(n >= 0, 400, "Invalid cursor");
            return n;
        } catch (NumberFormatException e) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Invalid cursor");
        }
    }
}
