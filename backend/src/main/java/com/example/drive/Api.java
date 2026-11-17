package com.example.drive;

import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;

import java.util.Arrays;
import java.util.Map;
import java.util.UUID;

@RestController
@RequestMapping("/api/v1")
public class Api {
    static final String ZERO = "00000000-0000-0000-0000-000000000000";
    final Drive d;

    public Api(Drive d) {
        this.d = d;
    }

    Drive.Actor actor(Authentication a) {
        return d.actor(a);
    }

    UUID ns(Authentication a, UUID ns) {
        return ns == null ? d.home(actor(a)) : ns;
    }

    @GetMapping("/me")
    Object me(Authentication a) {
        return d.profile(actor(a));
    }

    @PostMapping("/me/deactivate")
    void deactivate(Authentication a) {
        d.deactivate(actor(a));
    }

    @PostMapping("/folders")
    Object folder(Authentication a, @RequestParam(required = false) UUID ns, @RequestBody Folder r, @RequestHeader(value = "Idempotency-Key", required = false) String key) {
        UUID target = ns(a, ns);
        return d.idempotent(actor(a), target, key, Arrays.asList("folder", r.parent, r.name), () -> d.createFolder(actor(a), target, r.parent, r.name));
    }

    @PostMapping("/folders/{id}/delete-safe")
    Object deleteTree(Authentication a, @RequestParam(required = false) UUID ns, @PathVariable UUID id,
                      @RequestBody TreeDelete r, @RequestHeader("Idempotency-Key") String key) {
        UUID target = ns(a, ns);
        return d.idempotent(actor(a), target, key, Arrays.asList("delete-tree", id, r.expected, r.subtreeHash),
                () -> d.deleteTree(actor(a), target, id, r.expected, r.subtreeHash));
    }

    @GetMapping("/files")
    Object list(Authentication a, @RequestParam(required = false) UUID ns, @RequestParam(required = false) UUID parent, @RequestParam(defaultValue = "") String q, @RequestParam(defaultValue = ZERO) UUID after, @RequestParam(defaultValue = "100") int limit) {
        return d.list(actor(a), ns(a, ns), parent, q, after, limit);
    }

    @GetMapping("/search")
    Object search(Authentication a, @RequestParam(required = false) UUID ns, @RequestParam(required = false) UUID folder,
                  @RequestParam(defaultValue = "") String q, @RequestParam(required = false) String type, @RequestParam(required = false) String extension,
                  @RequestParam(required = false) String owner, @RequestParam(required = false) java.time.Instant since, @RequestParam(required = false) java.time.Instant until,
                  @RequestParam(defaultValue = ZERO) UUID after, @RequestParam(defaultValue = "100") int limit) {
        return d.search(actor(a), ns(a, ns), folder, q, type, extension, owner, since, until, after, limit);
    }

    @GetMapping("/files/{id}")
    Object read(Authentication a, @RequestParam(required = false) UUID ns, @PathVariable UUID id) {
        return d.read(actor(a), ns(a, ns), id);
    }

    @PatchMapping("/files/{id}")
    Object mutate(Authentication a, @RequestParam(required = false) UUID ns, @PathVariable UUID id, @RequestBody Mutation r, @RequestHeader(value = "Idempotency-Key", required = false) String key) {
        UUID target = ns(a, ns);
        return d.idempotent(actor(a), target, key, Arrays.asList("mutate", id, r.expected, r.action, r.name, r.parent), () -> d.mutate(actor(a), target, id, r.expected, r.action, r.name, r.parent));
    }

    @GetMapping("/files/{id}/versions")
    Object versions(Authentication a, @RequestParam(required = false) UUID ns, @PathVariable UUID id, @RequestParam(required = false) UUID before, @RequestParam(defaultValue = "100") int limit) {
        return d.versions(actor(a), ns(a, ns), id, before, limit);
    }

    @GetMapping("/files/{id}/download")
    Object download(Authentication a, @RequestParam(required = false) UUID ns, @PathVariable UUID id, @RequestParam(required = false) UUID version) {
        return d.download(actor(a), ns(a, ns), id, version);
    }

    @PostMapping("/files/{id}/versions/{version}/restore")
    Object restore(Authentication a, @RequestParam(required = false) UUID ns, @PathVariable UUID id, @PathVariable UUID version, @RequestBody Revision r) {
        return d.restoreVersion(actor(a), ns(a, ns), id, version, r.expected);
    }

    @PostMapping("/files/{id}/versions/{version}/scan")
    Object scan(Authentication a, @RequestParam(required = false) UUID ns, @PathVariable UUID id, @PathVariable UUID version) {
        return d.rescan(actor(a), ns(a, ns), id, version);
    }

    @PostMapping("/uploads")
    Object upload(Authentication a, @RequestParam(required = false) UUID ns, @RequestHeader("Idempotency-Key") String key, @RequestBody Drive.UploadRequest r) {
        return d.initiate(actor(a), ns(a, ns), key, r);
    }

    @GetMapping("/uploads/{id}")
    Object status(Authentication a, @RequestParam(required = false) UUID ns, @PathVariable UUID id) {
        return d.status(actor(a), ns(a, ns), id);
    }

    @PostMapping({"/uploads/{id}/parts", "/uploads/{id}/parts/sign"})
    Object part(Authentication a, @RequestParam(required = false) UUID ns, @PathVariable UUID id, @RequestBody Part r) {
        return d.part(actor(a), ns(a, ns), id, r.number, r.sha256);
    }

    @GetMapping("/uploads/{id}/parts")
    Object parts(Authentication a, @RequestParam(required = false) UUID ns, @PathVariable UUID id) {
        return d.status(actor(a), ns(a, ns), id).getOrDefault("parts", java.util.List.of());
    }

    @PostMapping("/uploads/{id}/resume")
    Object resume(Authentication a, @RequestParam(required = false) UUID ns, @PathVariable UUID id) {
        return d.status(actor(a), ns(a, ns), id);
    }

    @PostMapping("/uploads/{id}/complete")
    Object complete(Authentication a, @RequestParam(required = false) UUID ns, @PathVariable UUID id) {
        return org.springframework.http.ResponseEntity.accepted().body(d.requestCompletion(actor(a), ns(a, ns), id));
    }

    @PostMapping("/uploads/{id}/abort")
    void abort(Authentication a, @RequestParam(required = false) UUID ns, @PathVariable UUID id) {
        d.abort(actor(a), ns(a, ns), id);
    }

    @GetMapping("/sync/changes")
    Object changes(Authentication a, @RequestParam(required = false) UUID ns, @RequestParam(defaultValue = "0") String cursor, @RequestParam(defaultValue = "100") int limit) {
        return d.changes(actor(a), ns(a, ns), cursor, limit);
    }

    @GetMapping("/sync/snapshot")
    Object snapshot(Authentication a, @RequestParam(required = false) UUID ns, @RequestParam(defaultValue = ZERO) UUID after, @RequestParam(defaultValue = "100") int limit) {
        return d.snapshot(actor(a), ns(a, ns), after, limit);
    }

    @PostMapping("/sync/ack")
    void ack(Authentication a, @RequestParam(required = false) UUID ns, @RequestBody Ack r) {
        d.ack(actor(a), ns(a, ns), r.device, r.cursor);
    }

    @PostMapping("/devices")
    Object device(Authentication a, @RequestParam(required = false) UUID ns, @RequestBody Device r) {
        return Map.of("id", d.device(actor(a), ns(a, ns), r.name));
    }

    @GetMapping("/devices")
    Object devices(Authentication a, @RequestParam(required = false) UUID ns) {
        return d.devices(actor(a), ns(a, ns));
    }

    @GetMapping("/files/{id}/shares")
    Object shares(Authentication a, @RequestParam(required = false) UUID ns, @PathVariable UUID id) {
        return d.shares(actor(a), ns(a, ns), id);
    }

    @PostMapping("/files/{id}/shares")
    Object share(Authentication a, @RequestParam(required = false) UUID ns, @PathVariable UUID id, @RequestBody Grant r) {
        return Map.of("id", d.share(actor(a), ns(a, ns), id, r.subject, r.role));
    }

    @DeleteMapping("/files/{id}/shares/{grant}")
    void revoke(Authentication a, @RequestParam(required = false) UUID ns, @PathVariable UUID id, @PathVariable UUID grant) {
        d.revoke(actor(a), ns(a, ns), id, grant);
    }

    @PostMapping("/files/{id}/share-links")
    Object link(Authentication a, @RequestParam(required = false) UUID ns, @PathVariable UUID id, @RequestBody Link r) {
        return d.link(actor(a), ns(a, ns), id, r.seconds);
    }

    @GetMapping("/public/{token}")
    Object publicDownload(@PathVariable String token) {
        return d.publicDownload(token);
    }

    @PostMapping("/files/{id}/copy")
    Object copy(Authentication a, @RequestParam(required = false) UUID ns, @PathVariable UUID id, @RequestBody Folder r) {
        return d.copy(actor(a), ns(a, ns), id, r.parent, r.name);
    }

    @PostMapping("/files/{id}/retention")
    Object retain(Authentication a, @RequestParam(required = false) UUID ns, @PathVariable UUID id, @RequestParam int keep) {
        return Map.of("removed", d.retain(actor(a), ns(a, ns), id, keep));
    }

    public record Folder(UUID parent, String name) {
    }

    public record TreeDelete(long expected, String subtreeHash) {
    }

    public record Mutation(long expected, String action, String name, UUID parent) {
    }

    public record Part(int number, String sha256) {
    }

    public record Revision(long expected) {
    }

    public record Grant(String subject, String role) {
    }

    public record Link(long seconds) {
    }

    public record Device(String name) {
    }

    public record Ack(UUID device, long cursor) {
    }

}
