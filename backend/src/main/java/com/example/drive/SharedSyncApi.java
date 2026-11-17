package com.example.drive;

import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;

import java.util.UUID;

@RestController
@RequestMapping("/api/v1/sync/subscriptions")
public class SharedSyncApi {
    private final Drive drive;
    private final SharedSync sync;

    public SharedSyncApi(Drive drive, SharedSync sync) {
        this.drive = drive;
        this.sync = sync;
    }

    @PostMapping
    Object create(Authentication auth, @RequestBody Request request) {
        Rules.require(request.namespace() != null && request.root() != null, 400, "Namespace and root required");
        return sync.create(drive.actor(auth), request.namespace(), request.root());
    }

    @GetMapping("/{id}")
    Object read(Authentication auth, @PathVariable UUID id) {
        return sync.read(drive.actor(auth), id);
    }

    @DeleteMapping("/{id}")
    void remove(Authentication auth, @PathVariable UUID id) {
        sync.remove(drive.actor(auth), id);
    }

    @GetMapping("/{id}/changes")
    Object changes(Authentication auth, @PathVariable UUID id, @RequestParam(defaultValue = "0") String cursor,
                   @RequestParam(defaultValue = "100") int limit) {
        return sync.changes(drive.actor(auth), id, cursor, limit);
    }

    public record Request(UUID namespace, UUID root) {
    }
}
