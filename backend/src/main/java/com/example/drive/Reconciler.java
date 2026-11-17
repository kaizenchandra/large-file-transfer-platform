package com.example.drive;

import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.util.UUID;

@Component
public class Reconciler {
    final Drive d;
    final MeterRegistry metrics;

    public Reconciler(Drive d, MeterRegistry metrics) {
        this.d = d;
        this.metrics = metrics;
    }

    @Scheduled(fixedDelayString = "${drive.reconcile-ms:60000}")
    public void reconcile() { reconcileTenant(null); }

    public void reconcileTenant(String tenant) {
        var sessions = d.db.queryForList("SELECT u.id,u.ns,u.state,u.actor,n.tenant FROM upload_sessions u JOIN namespaces n ON n.id=u.ns WHERE ((u.state='COMPLETING' AND (u.completion_lease IS NULL OR u.completion_lease<now())) OR (u.state IN ('INITIATED','UPLOADING') AND u.expires<now())) AND u.retry_at<=now() AND n.active AND (?::text IS NULL OR n.tenant=?) ORDER BY u.retry_at LIMIT 50",tenant,tenant);
        for (var u : sessions) {
            try {
                var a = new Drive.Actor(u.get("tenant").toString(), u.get("actor").toString());
                UUID ns = (UUID) u.get("ns"), id = (UUID) u.get("id");
                if (u.get("state").equals("COMPLETING")) d.complete(a, ns, id);
                else d.abort(a, ns, id);
                metrics.counter("drive.reconciliation", "outcome", "success").increment();
            } catch (Exception e) {
                if (e instanceof org.springframework.web.server.ResponseStatusException r && r.getStatusCode().value() == 422 &&
                        d.one("SELECT state FROM upload_sessions WHERE id=?", u.get("id")).get("state").equals("QUARANTINED")) {
                    metrics.counter("drive.reconciliation", "outcome", "quarantined").increment();
                    continue;
                }
                // Authorization loss, stale revisions and failed integrity cannot be repaired by retrying.
                // Aborting never deletes published versions or completed object bytes.
                if (e instanceof org.springframework.web.server.ResponseStatusException r && java.util.Set.of(403, 404, 412, 422).contains(r.getStatusCode().value())) {
                    try {
                        d.abort(new Drive.Actor(u.get("tenant").toString(), u.get("actor").toString()), (UUID) u.get("ns"), (UUID) u.get("id"));
                        metrics.counter("drive.reconciliation", "outcome", "aborted").increment();
                        continue;
                    } catch (Exception abortFailure) { /* Keep reservation until storage abort is confirmed. */ }
                }
                d.db.update("UPDATE upload_sessions SET failures=least(failures+1,30),retry_at=now()+least(3600,30*power(2,least(failures,7))) * interval '1 second' WHERE id=? AND state IN ('INITIATED','UPLOADING','COMPLETING')", u.get("id"));
                metrics.counter("drive.reconciliation", "outcome", "failure").increment();
            }
        }
    }
}
