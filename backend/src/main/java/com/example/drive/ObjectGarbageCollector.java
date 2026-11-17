package com.example.drive;

import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.UUID;

/**
 * Opt-in mark/grace/recheck collection for durable upload keys only. Never delete by object age alone.
 */
@Component
public class ObjectGarbageCollector {
    private final Drive drive;
    private final MeterRegistry metrics;
    private final boolean enabled;
    private final long grace;

    public ObjectGarbageCollector(Drive drive, MeterRegistry metrics,
                                  @Value("${drive.gc-enabled:false}") boolean enabled,
                                  @Value("${drive.gc-grace-seconds:86400}") long grace) {
        if (grace < 600) throw new IllegalArgumentException("GC grace must be at least 600 seconds");
        this.drive = drive;
        this.metrics = metrics;
        this.enabled = enabled;
        this.grace = grace;
    }

    int mark(UUID ns) {
        return drive.tx.execute(status -> {
            drive.one("SELECT id FROM namespaces WHERE id=? FOR UPDATE", ns);
            // Every published/restored/copied reference is checked, including trash and retained history.
            return drive.db.update("""
                    INSERT INTO garbage_objects(object_key,ns)
                    SELECT u.object_key,u.ns FROM upload_sessions u WHERE u.ns=?
                     AND u.state IN ('COMPLETED','ABORTED')
                     AND NOT EXISTS(SELECT 1 FROM file_versions v WHERE v.object_key=u.object_key)
                     AND NOT EXISTS(SELECT 1 FROM garbage_objects g WHERE g.object_key=u.object_key)
                    ORDER BY u.id LIMIT 50 ON CONFLICT DO NOTHING
                    """, ns);
        });
    }

    int sweep(UUID ns) {
        var candidates = drive.db.queryForList("SELECT object_key FROM garbage_objects WHERE ns=? AND deleted_at IS NULL AND marked_at<? AND retry_at<=now() ORDER BY marked_at LIMIT 50",
                ns, Timestamp.from(Instant.now().minusSeconds(grace)));
        int deleted = 0;
        for (var candidate : candidates) {
            String key = candidate.get("object_key").toString();
            try {
                boolean removed = drive.tx.execute(status -> {
                    // The same lock fences version restore, copy, retention, completion and other collectors.
                    drive.one("SELECT id FROM namespaces WHERE id=? FOR UPDATE", ns);
                    var rows = drive.db.queryForList("SELECT * FROM garbage_objects WHERE object_key=? AND ns=?", key, ns);
                    if (rows.isEmpty()) return false;
                    var row = rows.getFirst();
                    if (row.get("deleted_at") != null || ((Timestamp) row.get("marked_at")).toInstant().isAfter(Instant.now().minusSeconds(grace))
                            || ((Timestamp) row.get("retry_at")).toInstant().isAfter(Instant.now())) return false;
                    if(Boolean.TRUE.equals(drive.db.queryForObject("SELECT EXISTS(SELECT 1 FROM processing_jobs WHERE id=? AND state='RUNNING' AND lease_until>now())",Boolean.class,row.get("processing_job"))))return false;
                    if(Boolean.TRUE.equals(drive.db.queryForObject("SELECT EXISTS(SELECT 1 FROM processed_artifacts WHERE object_key=?)",Boolean.class,key))) {
                        drive.db.update("UPDATE garbage_objects SET retry_at=now()+interval '1 hour' WHERE object_key=?",key);
                        return false;
                    }
                    boolean referenced = Boolean.TRUE.equals(drive.db.queryForObject("""
                            SELECT EXISTS(SELECT 1 FROM file_versions WHERE object_key=?) OR
                             EXISTS(SELECT 1 FROM upload_sessions WHERE object_key=? AND state NOT IN ('COMPLETED','ABORTED'))
                            """, Boolean.class, key, key));
                    if (referenced) {
                        drive.db.update("DELETE FROM garbage_objects WHERE object_key=?", key);
                        return false;
                    }
                    drive.objects.delete(key); // Ambiguous success is safe to repeat; keys are never reused.
                    drive.db.update("UPDATE garbage_objects SET deleted_at=now() WHERE object_key=?", key);
                    drive.db.update("INSERT INTO audit_records(ns,actor,action,entity) SELECT ns,'system:gc','GC_DELETE',id FROM upload_sessions WHERE object_key=?", key);
                    return true;
                });
                if (removed) deleted++;
            } catch (RuntimeException failure) {
                drive.db.update("UPDATE garbage_objects SET failures=least(failures+1,30),retry_at=now()+interval '5 minutes' WHERE object_key=? AND deleted_at IS NULL", key);
                metrics.counter("drive.gc.failures").increment();
            }
        }
        metrics.counter("drive.gc.deleted").increment(deleted);
        return deleted;
    }

    @Scheduled(fixedDelayString = "${drive.gc-ms:60000}")
    public void maintain() {
        if (!enabled) return;
        var namespaces = drive.db.queryForList("""
                SELECT n.id FROM namespaces n WHERE
                 EXISTS(SELECT 1 FROM garbage_objects g WHERE g.ns=n.id AND g.deleted_at IS NULL AND g.marked_at<? AND g.retry_at<=now()) OR
                 EXISTS(SELECT 1 FROM upload_sessions u WHERE u.ns=n.id AND u.state IN ('COMPLETED','ABORTED')
                   AND NOT EXISTS(SELECT 1 FROM file_versions v WHERE v.object_key=u.object_key)
                   AND NOT EXISTS(SELECT 1 FROM garbage_objects g WHERE g.object_key=u.object_key))
                ORDER BY n.id LIMIT 50
                """, Timestamp.from(Instant.now().minusSeconds(grace)));
        for (var row : namespaces) {
            UUID ns = (UUID) row.get("id");
            try {
                metrics.counter("drive.gc.marked").increment(mark(ns));
                sweep(ns);
            } catch (RuntimeException failure) {
                metrics.counter("drive.gc.failures").increment();
            }
        }
    }
}
