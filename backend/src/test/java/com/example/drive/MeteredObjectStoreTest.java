package com.example.drive;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;

class MeteredObjectStoreTest {
    @Test
    void recordsFailuresWithoutCountingUnverifiedBytes() {
        var delegate = mock(S3Objects.class);
        var metrics = new SimpleMeterRegistry();
        var store = new MeteredObjectStore(delegate, metrics);
        var failure = new IllegalStateException("storage failed");
        doThrow(failure).doNothing().when(delegate).verify("private-key", 42, "checksum");
        assertSame(failure, assertThrows(IllegalStateException.class, () -> store.verify("private-key", 42, "checksum")));
        assertNull(metrics.find("drive.storage.verified.bytes").counter());
        store.verify("private-key", 42, "checksum");
        assertEquals(42, metrics.get("drive.storage.verified.bytes").counter().count());
        assertEquals(1, metrics.get("drive.storage").tags("operation", "verify", "outcome", "failure").timer().count());
        assertEquals(1, metrics.get("drive.storage").tags("operation", "verify", "outcome", "success").timer().count());
        assertTrue(metrics.getMeters().stream().flatMap(m -> m.getId().getTags().stream()).noneMatch(t -> t.getValue().contains("private-key")));
    }
}
