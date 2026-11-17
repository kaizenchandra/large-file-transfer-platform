package com.example.drive;

import org.springframework.jdbc.core.JdbcTemplate;
import java.util.UUID;
import java.util.concurrent.*;

/** One bounded renewal task per active worker; no transaction spans storage I/O. */
final class CompletionHeartbeat implements AutoCloseable {
    private final ScheduledExecutorService executor = Executors.newSingleThreadScheduledExecutor(Thread.ofPlatform().daemon().name("completion-heartbeat").factory());
    CompletionHeartbeat(JdbcTemplate db, UUID id, long fence) {
        executor.scheduleWithFixedDelay(() -> {
            try { db.update("UPDATE upload_sessions SET completion_lease=now()+interval '60 seconds' WHERE id=? AND completion_fence=? AND state='COMPLETING' AND completion_lease>now()",id,fence); }
            catch (Exception unavailable) { /* Expiration fences promotion if the database stays unavailable. */ }
        }, 15, 15, TimeUnit.SECONDS);
    }
    public void close() { executor.shutdownNow(); }
}
