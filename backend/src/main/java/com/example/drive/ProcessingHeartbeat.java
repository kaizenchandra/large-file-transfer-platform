package com.example.drive;

import org.springframework.jdbc.core.JdbcTemplate;
import java.util.UUID;
import java.util.concurrent.*;

/** One bounded renewal task per active worker; no transaction spans storage I/O. */
final class ProcessingHeartbeat implements AutoCloseable {
    private final ScheduledExecutorService executor = Executors.newSingleThreadScheduledExecutor(Thread.ofPlatform().daemon().name("processing-heartbeat").factory());
    ProcessingHeartbeat(JdbcTemplate db, Object id, Object fence) {
        executor.scheduleWithFixedDelay(() -> {
            try { db.update("UPDATE processing_jobs SET lease_until=now()+interval '60 seconds' WHERE id=? AND fence=? AND state='RUNNING' AND lease_until>now()",id,fence); }
            catch (Exception unavailable) { /* Expiration fences promotion if the database stays unavailable. */ }
        }, 15, 15, TimeUnit.SECONDS);
    }
    public void close() { executor.shutdownNow(); }
}
