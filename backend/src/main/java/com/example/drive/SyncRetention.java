package com.example.drive;

import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.UUID;

/**
 * Removes only a contiguous committed prefix, atomically with its advertised cursor floor.
 */
@Component
public class SyncRetention {
    private final Drive drive;
    private final MeterRegistry metrics;
    private final int days;

    public SyncRetention(Drive drive, MeterRegistry metrics, @Value("${drive.sync-retention-days:0}") int days) {
        if (days < 0) throw new IllegalArgumentException("sync-retention-days must be nonnegative");
        this.drive = drive;
        this.metrics = metrics;
        this.days = days;
    }

    @Scheduled(fixedDelayString = "${drive.sync-retention-ms:3600000}")
    public void maintain() {
        if (days == 0) return; // Explicit opt-in until operators choose their offline recovery window.
        Instant cutoff = Instant.now().minus(java.time.Duration.ofDays(days));
        var candidates = drive.db.queryForList("""
                SELECT n.id FROM namespaces n
                JOIN LATERAL (SELECT created FROM change_log WHERE ns=n.id ORDER BY seq LIMIT 1) c ON true
                WHERE c.created<? ORDER BY n.id LIMIT 50
                """, Timestamp.from(cutoff));
        for (var row : candidates) {
            try {
                metrics.counter("drive.sync.compacted").increment(compact((UUID) row.get("id"), cutoff, 1000));
            } catch (RuntimeException e) {
                metrics.counter("drive.sync.compaction.failures").increment();
            }
        }
    }

    int compact(UUID namespace, Instant cutoff, int batchSize) {
        if (batchSize < 1 || batchSize > 10000) throw new IllegalArgumentException("Batch must be 1..10000");
        return drive.tx.execute(status -> {
            drive.one("SELECT id FROM namespaces WHERE id=? FOR UPDATE", namespace);
            // Do not skip newer records even when transaction timestamps differ from commit order.
            var prefix = drive.db.queryForList("SELECT seq,created FROM change_log WHERE ns=? ORDER BY seq LIMIT ?", namespace, batchSize);
            long boundary = -1;
            for (var row : prefix) {
                if (!((Timestamp) row.get("created")).toInstant().isBefore(cutoff)) break;
                boundary = ((Number) row.get("seq")).longValue();
            }
            if (boundary < 0) return 0;
            int removed = drive.db.update("DELETE FROM change_log WHERE ns=? AND seq<=?", namespace, boundary);
            drive.db.update("UPDATE namespaces SET sync_floor=? WHERE id=?", boundary, namespace);
            return removed;
        });
    }
}
