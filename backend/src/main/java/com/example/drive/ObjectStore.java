package com.example.drive;

import java.util.List;
import java.util.Map;

public interface ObjectStore {
    default void put(String key, byte[] bytes, String contentType) { throw new UnsupportedOperationException("Artifact writes unavailable"); }

    String initiate(String key);

    Map<String, Object> part(String key, String upload, int number, String checksum);

    List<Map<String, Object>> parts(String key, String upload);

    void complete(String key, String upload, long size, long partSize, Map<Integer, String> checksums);

    void verify(String key, long size, String sha256);

    java.io.InputStream open(String key);

    void abort(String key, String upload);

    void delete(String key);

    String download(String key, String name);
}
