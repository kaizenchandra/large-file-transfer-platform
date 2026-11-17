package com.example.drive;

import tools.jackson.databind.ObjectMapper;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.core.Authentication;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.*;
import java.util.function.Supplier;

import static com.example.drive.Rules.*;

@Service
public class Drive {
    @org.springframework.beans.factory.annotation.Value("${MEDIA_SANDBOX_URL:}") String mediaEndpoint="";
    @org.springframework.beans.factory.annotation.Value("${MEDIA_SANDBOX_TOKEN:}") String mediaToken="";
    @org.springframework.beans.factory.annotation.Value("${drive.admission.max-active-uploads:16}") int maxActiveUploads=16;
    @org.springframework.beans.factory.annotation.Value("${drive.admission.sessions-per-minute:120}") int sessionsPerMinute=120;
    final JdbcTemplate db;
    final TransactionTemplate tx;
    final ObjectStore objects;
    final ObjectMapper json;
    final MalwareGate scans;

    public Drive(JdbcTemplate db, TransactionTemplate tx, ObjectStore objects, ObjectMapper json, MalwareGate scans) {
        this.db = db;
        this.tx = tx;
        this.objects = objects;
        this.json = json;
        this.scans = scans;
    }

    public Actor actor(Authentication a) {
        return a instanceof JwtAuthenticationToken j ? new Actor(j.getToken().getClaimAsString("tenant"), a.getName()) : new Actor("local", a.getName());
    }

    public UUID home(Actor a) {
        require(a.tenant() != null && !a.tenant().isBlank() && a.subject() != null && !a.subject().isBlank(), 401, "Identity claims required");
        // Random candidate plus unique identity constraint avoids ambiguous tenant:subject encodings.
        db.update("INSERT INTO namespaces(id,tenant,subject) VALUES(?,?,?) ON CONFLICT(tenant,subject) DO NOTHING", UUID.randomUUID(), a.tenant(), a.subject());
        var n = one("SELECT id,active FROM namespaces WHERE tenant=? AND subject=?", a.tenant(), a.subject());
        require(Boolean.TRUE.equals(n.get("active")), 403, "Account inactive");
        return uuid(n.get("id"));
    }

    Map<String, Object> namespace(UUID ns) {
        return one("SELECT * FROM namespaces WHERE id=?", ns);
    }

    public Map<String, Object> one(String sql, Object... args) {
        var rows = db.queryForList(sql, args);
        require(!rows.isEmpty(), 404, "Not found");
        return rows.getFirst();
    }

    public <T> T locked(Actor a, UUID ns, Supplier<T> work) {
        return tx.execute(s -> {
            var n = one("SELECT * FROM namespaces WHERE id=? FOR UPDATE", ns);
            require(n.get("tenant").equals(a.tenant()) && Boolean.TRUE.equals(n.get("active")), 403, "Namespace unavailable");
            return work.get();
        });
    }

    public Map<String, Object> node(UUID ns, UUID id) {
        return one("SELECT * FROM file_nodes WHERE ns=? AND id=?", ns, id);
    }

    UUID uuid(Object x) {
        return x == null ? null : UUID.fromString(x.toString());
    }

    long num(Object x) {
        return ((Number) x).longValue();
    }

    boolean visible(UUID ns, UUID id) {
        return Boolean.TRUE.equals(one("WITH RECURSIVE path AS (SELECT id,parent,deleted FROM file_nodes WHERE ns=? AND id=? UNION ALL SELECT n.id,n.parent,n.deleted FROM file_nodes n JOIN path p ON n.id=p.parent) SELECT NOT bool_or(deleted) AS ok FROM path", ns, id).get("ok"));
    }

    public void access(Actor a, UUID ns, UUID id, boolean edit, boolean owner) {
        var n = namespace(ns);
        require(a.tenant().equals(n.get("tenant")) && Boolean.TRUE.equals(n.get("active")), 404, "Not found");
        if (id != null) node(ns,id); // Namespace ownership never authorizes a foreign file ID.
        if (n.get("subject").equals(a.subject())) return;
        require(!owner && id != null, 403, "Owner required");
        var rows = db.queryForList("WITH RECURSIVE path AS (SELECT id,parent FROM file_nodes WHERE ns=? AND id=? UNION ALL SELECT n.id,n.parent FROM file_nodes n JOIN path p ON n.id=p.parent) SELECT s.role FROM path p JOIN sharing_permissions s ON s.node=p.id WHERE s.subject=?", ns, id, a.subject());
        require(rows.stream().anyMatch(r -> !edit || r.get("role").equals("EDITOR")), 403, "Access denied");
    }

    void parent(Actor a, UUID ns, UUID parent) {
        access(a, ns, parent, true, false);
        if (parent != null) {
            var p = node(ns, parent);
            require(p.get("kind").equals("FOLDER") && visible(ns, parent), 409, "Parent unavailable");
        }
    }

    void revision(Map<String, Object> node, long expected) {
        require(num(node.get("revision")) == expected, 412, "Revision conflict; preserve local copy");
    }

    void event(Actor a, UUID ns, UUID id, String action) {
        var n = node(ns, id);
        long seq = num(one("UPDATE namespaces SET seq=seq+1 WHERE id=? RETURNING seq", ns).get("seq"));
        try {
            db.update("INSERT INTO change_log(ns,seq,node,payload,action) VALUES(?,?,?,?::jsonb,?)", ns, seq, id, json.writeValueAsString(n), action);
        } catch (tools.jackson.core.JacksonException e) {
            throw new IllegalStateException(e);
        }
        db.update("INSERT INTO audit_records(ns,actor,action,entity) VALUES(?,?,?,?)", ns, a.subject(), action, id);
    }

    public Map<String, Object> idempotent(Actor a, UUID ns, String key, Object request, Supplier<Map<String, Object>> work) {
        if (key == null) return work.get();
        require(!key.isBlank() && key.length() <= 128, 400, "Invalid Idempotency-Key");
        return locked(a, ns, () -> {
            var old = db.queryForList("SELECT fingerprint,response FROM client_operations WHERE ns=? AND actor=? AND operation_key=?", ns, a.subject(), key);
            try {
                String fingerprint = hash(json.writeValueAsString(request));
                if (!old.isEmpty()) {
                    require(old.getFirst().get("fingerprint").equals(fingerprint), 409, "Idempotency key reused");
                    return json.readValue(old.getFirst().get("response").toString(), new tools.jackson.core.type.TypeReference<Map<String, Object>>() {
                    });
                }
                var result = work.get();
                db.update("INSERT INTO client_operations(ns,actor,operation_key,fingerprint,response) VALUES(?,?,?,?,?::jsonb)", ns, a.subject(), key, fingerprint, json.writeValueAsString(result));
                return result;
            } catch (tools.jackson.core.JacksonException e) {
                throw new IllegalStateException(e);
            }
        });
    }

    public Map<String, Object> createFolder(Actor a, UUID ns, UUID parent, String name) {
        return locked(a, ns, () -> {
            parent(a, ns, parent);
            UUID id = UUID.randomUUID();
            db.update("INSERT INTO file_nodes(id,ns,parent,name,kind) VALUES(?,?,?,?,'FOLDER')", id, ns, parent, name(name));
            event(a, ns, id, "CREATE");
            return node(ns, id);
        });
    }

    public Map<String, Object> mutate(Actor a, UUID ns, UUID id, long expected, String action, String name, UUID parent) {
        return locked(a, ns, () -> {
            require(action != null, 400, "Action required");
            access(a, ns, id, true, action.equals("restore") || action.equals("delete"));
            var n = node(ns, id);
            revision(n, expected);
            if (action.equals("move") || action.equals("relocate")) {
                if (action.equals("relocate")) require(visible(ns, id), 409, "Source unavailable");
                parent(a, ns, parent);
                if (parent != null)
                    require(!db.queryForObject("WITH RECURSIVE path AS (SELECT id,parent FROM file_nodes WHERE id=? UNION ALL SELECT n.id,n.parent FROM file_nodes n JOIN path p ON n.id=p.parent) SELECT EXISTS(SELECT 1 FROM path WHERE id=?)", Boolean.class, parent, id), 409, "Folder cycle");
                db.update("UPDATE file_nodes SET parent=? WHERE id=?", parent, id);
                if (action.equals("relocate")) db.update("UPDATE file_nodes SET name=? WHERE id=?", name(name), id);
            } else if (action.equals("rename")) db.update("UPDATE file_nodes SET name=? WHERE id=?", name(name), id);
            else if (action.equals("delete") || action.equals("restore")) {
                if (action.equals("restore")) parent(a, ns, uuid(n.get("parent")));
                db.update("UPDATE file_nodes SET deleted=? WHERE id=?", action.equals("delete"), id);
            } else require(false, 400, "Unknown action");
            db.update("UPDATE file_nodes SET revision=revision+1,updated=now() WHERE id=?", id);
            event(a, ns, id, action);
            return node(ns, id);
        });
    }

    /**
     * Fingerprint every node revision, including tombstones, in a deterministic subtree order.
     */
    String subtreeHash(UUID ns, UUID root) {
        var rows = db.queryForList("""
                WITH RECURSIVE tree AS (
                 SELECT id,revision FROM file_nodes WHERE ns=? AND id=?
                 UNION ALL SELECT n.id,n.revision FROM file_nodes n JOIN tree p ON n.parent=p.id WHERE n.ns=?
                ) SELECT id,revision FROM tree ORDER BY id
                """, ns, root, ns);
        StringBuilder manifest = new StringBuilder();
        for (var row : rows) manifest.append(row.get("id")).append(':').append(num(row.get("revision"))).append('\n');
        return hash(manifest.toString());
    }

    public Map<String, Object> deleteTree(Actor actor, UUID ns, UUID id, long expected, String manifest) {
        return locked(actor, ns, () -> {
            access(actor, ns, id, true, true);
            var folder = read(actor, ns, id);
            require(folder.get("kind").equals("FOLDER"), 400, "Folder required");
            revision(folder, expected);
            checksum(manifest);
            require(subtreeHash(ns, id).equals(manifest), 412, "Subtree changed; restore remote state before deleting");
            // One ancestor tombstone; immutable descendant versions and quota accounting are retained.
            return mutate(actor, ns, id, expected, "delete", null, null);
        });
    }

    public List<Map<String, Object>> list(Actor a, UUID ns, UUID parent, String query, UUID after, int limit) {
        access(a, ns, parent, false, false);
        require(limit > 0 && limit <= 500, 400, "Limit 1..500");
        if (parent != null) require(visible(ns, parent), 404, "Deleted folder");
        return db.queryForList("SELECT * FROM file_nodes WHERE ns=? AND parent IS NOT DISTINCT FROM ?::uuid AND NOT deleted AND lower(name) LIKE ? ESCAPE '!' AND id>? ORDER BY id LIMIT ?", ns, parent, "%" + query.toLowerCase(Locale.ROOT).replace("!", "!!").replace("%", "!%").replace("_", "!_") + "%", after, limit);
    }

    /**
     * Search an authorized subtree; a null folder searches the owner's entire namespace.
     */
    public List<Map<String, Object>> search(Actor a, UUID ns, UUID folder, String query, String kind, String extension, String owner, Instant since, Instant until, UUID after, int limit) {
        return locked(a, ns, () -> {
            access(a, ns, folder, false, folder == null);
            require(limit > 0 && limit <= 500, 400, "Limit 1..500");
            require(kind == null || List.of("FILE", "FOLDER").contains(kind), 400, "Type must be FILE or FOLDER");
            require(since == null || until == null || since.isBefore(until), 400, "Invalid date range");
            if (folder != null) {
                var f = read(a, ns, folder);
                require(f.get("kind").equals("FOLDER"), 400, "Folder required");
            }
            if (owner != null && !owner.equals(namespace(ns).get("subject"))) return List.of();
            String pattern = "%" + query.toLowerCase(Locale.ROOT).replace("!", "!!").replace("%", "!%").replace("_", "!_") + "%";
            // Starting at live roots prunes every deleted subtree before pagination.
            return db.queryForList("""
                            WITH RECURSIVE scope AS (
                             SELECT n.* FROM file_nodes n WHERE ns=? AND NOT deleted AND
                              ((?::uuid IS NULL AND parent IS NULL) OR (?::uuid IS NOT NULL AND parent=?::uuid))
                             UNION ALL SELECT n.* FROM file_nodes n JOIN scope p ON n.parent=p.id WHERE n.ns=? AND NOT n.deleted
                            ) SELECT * FROM scope WHERE id>? AND lower(name) LIKE ? ESCAPE '!'
                             AND (?::text IS NULL OR kind=?::text)
                             AND (?::text IS NULL OR (kind='FILE' AND right(lower(name),length(?::text)+1)='.'||lower(?::text)))
                             AND (?::timestamptz IS NULL OR updated>=?::timestamptz)
                             AND (?::timestamptz IS NULL OR updated<?::timestamptz)
                             ORDER BY id LIMIT ?
                            """, ns, folder, folder, folder, ns, after, pattern, kind, kind, extension, extension, extension,
                    since == null ? null : Timestamp.from(since), since == null ? null : Timestamp.from(since),
                    until == null ? null : Timestamp.from(until), until == null ? null : Timestamp.from(until), limit);
        });
    }

    public void deactivate(Actor a) {
        tx.execute(s -> {
            var rows = db.queryForList("SELECT * FROM namespaces WHERE tenant=? AND subject=? FOR UPDATE", a.tenant(), a.subject());
            if (rows.isEmpty() || !Boolean.TRUE.equals(rows.getFirst().get("active"))) return null;
            UUID ns = uuid(rows.getFirst().get("id"));
            // Confirm all storage aborts before releasing reservations or disabling the namespace.
            for (var u : db.queryForList("SELECT id,actor FROM upload_sessions WHERE ns=? AND state IN ('INITIATED','UPLOADING','COMPLETING')", ns))
                abort(new Actor(a.tenant(), u.get("actor").toString()), ns, uuid(u.get("id")));
            db.update("UPDATE namespaces SET active=false WHERE id=?", ns);
            db.update("INSERT INTO audit_records(ns,actor,action,entity) VALUES(?,?,'DEACTIVATE',?)", ns, a.subject(), ns);
            return null;
        });
    }

    public Map<String, Object> profile(Actor a) {
        return namespace(home(a));
    }

    public Map<String, Object> read(Actor a, UUID ns, UUID id) {
        access(a, ns, id, false, false);
        require(visible(ns, id), 404, "Deleted");
        return node(ns, id);
    }

    public List<Map<String, Object>> versions(Actor a, UUID ns, UUID id) {
        return versions(a, ns, id, null, 500);
    }

    public List<Map<String, Object>> versions(Actor a, UUID ns, UUID id, UUID before, int limit) {
        return locked(a, ns, () -> {
            read(a, ns, id);
            require(limit > 0 && limit <= 500, 400, "Limit 1..500");
            if (before == null)
                return db.queryForList("SELECT id,size,sha256,created FROM file_versions WHERE node=? ORDER BY created DESC,id DESC LIMIT ?", id, limit);
            var boundary = one("SELECT created,id FROM file_versions WHERE node=? AND id=?", id, before);
            return db.queryForList("SELECT id,size,sha256,created FROM file_versions WHERE node=? AND (created,id)<(?::timestamptz,?::uuid) ORDER BY created DESC,id DESC LIMIT ?", id, boundary.get("created"), before, limit);
        });
    }

    public Map<String,Object> authorizedVersion(Actor a,UUID ns,UUID id,UUID version){
        var node=read(a,ns,id);var result=one("SELECT * FROM file_versions WHERE node=? AND id=?",id,version==null?uuid(node.get("current_version")):version);scanAccess(result);return result;
    }
    public Map<String, Object> download(Actor a, UUID ns, UUID id, UUID version) {
        return locked(a, ns, () -> {
            var n = read(a, ns, id);
            var v = one("SELECT * FROM file_versions WHERE node=? AND id=?", id, version == null ? uuid(n.get("current_version")) : version);
            scanAccess(v);
            db.update("INSERT INTO audit_records(ns,actor,action,entity) VALUES(?,?,'DOWNLOAD_CREDENTIAL',?)",ns,a.subject(),id);
            return Map.of("url", objects.download(v.get("object_key").toString(), n.get("name").toString()), "size", v.get("size"), "sha256", v.get("sha256"), "expiresInSeconds", 60, "version", v.get("id"));
        });
    }

    void recordScan(String key, long size, String checksum, MalwareGate.Decision decision) {
        db.update("""
                INSERT INTO blob_scans(object_key,sha256,size,verdict,policy,engine) VALUES(?,?,?,?,?,?)
                ON CONFLICT(object_key) DO UPDATE SET sha256=excluded.sha256,size=excluded.size,
                 verdict=excluded.verdict,policy=excluded.policy,engine=excluded.engine,scanned_at=now()
                """, key, checksum, size, decision.verdict(), scans.policy(), decision.engine());
        db.update("UPDATE blob_scans SET covered_bytes=?,segments=?,scope=? WHERE object_key=?",decision.coveredBytes(),decision.segments(),decision.scope(),key);
        db.update("UPDATE upload_sessions SET scan_status=? WHERE object_key=?", decision.verdict(), key);
    }

    void scanAccess(Map<String, Object> version) {
        var reports = db.queryForList("SELECT * FROM blob_scans WHERE object_key=?", version.get("object_key"));
        if (reports.isEmpty()) {
            require(!scans.required(), 403, "Scan approval required");
            return;
        }
        var report = reports.getFirst();
        require(java.util.Set.of("CLEAN","BYPASSED").contains(report.get("verdict")), 403, "Content not approved by scanning policy");
        require(report.get("sha256").equals(version.get("sha256")) && num(report.get("size")) == num(version.get("size")), 403, "Scan identity mismatch");
        require(!scans.required() || (report.get("verdict").equals("CLEAN") && report.get("policy").equals(scans.policy())), 403, "Current scan approval required");
    }

    public Map<String, Object> rescan(Actor actor, UUID ns, UUID file, UUID version) {
        require(scans.required(), 409, "Enable required scanning before rescanning content");
        String verdict = locked(actor, ns, () -> {
            read(actor, ns, file);
            access(actor, ns, file, true, true);
            var v = one("SELECT * FROM file_versions WHERE node=? AND id=?", file, version);
            String key = v.get("object_key").toString();
            long size = num(v.get("size"));
            scans.admit(size);
            objects.verify(key, size, v.get("sha256").toString());
            var result = scans.inspect(objects, key, size);
            recordScan(key, size, v.get("sha256").toString(), result);
            db.update("INSERT INTO audit_records(ns,actor,action,entity) VALUES(?,?,?,?)", ns, actor.subject(), "RESCAN_" + result.verdict(), version);
            return result.verdict();
        });
        require(!verdict.equals("BLOCKED"), 422, "Content blocked by scanning policy");
        return Map.of("verdict", verdict, "policy", scans.policy());
    }

    public Map<String, Object> restoreVersion(Actor a, UUID ns, UUID id, UUID version, long expected) {
        return locked(a, ns, () -> {
            read(a, ns, id);
            access(a, ns, id, true, false);
            revision(node(ns, id), expected);
            var v = one("SELECT * FROM file_versions WHERE node=? AND id=?", id, version);
            scanAccess(v);
            long size = num(v.get("size"));
            reserve(ns, size);
            UUID next = UUID.randomUUID();
            db.update("INSERT INTO file_versions(id,node,object_key,size,sha256) VALUES(?,?,?,?,?)", next, id, v.get("object_key"), size, v.get("sha256"));
            db.update("UPDATE file_nodes SET current_version=?,revision=revision+1,updated=now() WHERE id=?", next, id);
            consume(ns, size);
            event(a, ns, id, "VERSION_RESTORE");
            return node(ns, id);
        });
    }

    void reserve(UUID ns, long size) {
        require(db.update("UPDATE namespaces SET reserved=reserved+? WHERE id=? AND used+reserved+quarantined+?<=quota", size, ns, size) == 1, 413, "Quota exceeded");
    }

    void consume(UUID ns, long size) {
        db.update("UPDATE namespaces SET reserved=reserved-?,used=used+? WHERE id=?", size, size, ns);
    }

    public Map<String, Object> initiate(Actor a, UUID ns, String idem, UploadRequest r) {
        return locked(a, ns, () -> {
            require(idem != null && idem.length() <= 128 && !idem.isBlank(), 400, "Idempotency-Key required");
            name(r.name);
            checksum(r.sha256);
            long partSize = partSize(r.size);
            String fp = hash(r.toString());
            var old = db.queryForList("SELECT * FROM upload_sessions WHERE ns=? AND actor=? AND idem=?", ns, a.subject(), idem);
            if (!old.isEmpty()) {
                require(old.getFirst().get("fingerprint").equals(fp), 409, "Idempotency key reused");
                return safeUpload(old.getFirst());
            }
            require(db.queryForObject("SELECT count(*) FROM upload_sessions WHERE ns=? AND state IN ('INITIATED','UPLOADING','COMPLETING')",Long.class,ns)<maxActiveUploads,429,"Concurrent upload limit reached");
            var admitted=db.queryForList("INSERT INTO api_rate_windows(ns,window_start,requests) VALUES(?,date_trunc('minute',now()),1) ON CONFLICT(ns,window_start) DO UPDATE SET requests=api_rate_windows.requests+1 WHERE api_rate_windows.requests<? RETURNING requests",ns,sessionsPerMinute);
            require(!admitted.isEmpty(),429,"Upload session rate limit reached");
            scans.admit(r.size);
            if (r.node == null) parent(a, ns, r.parent);
            if (r.node != null) {
                read(a, ns, r.node);
                access(a, ns, r.node, true, false);
                revision(node(ns, r.node), r.expected);
                require(node(ns, r.node).get("kind").equals("FILE"), 409, "Not a file");
            }
            reserve(ns, r.size);
            UUID id = UUID.randomUUID();
            String key = ns + "/" + id;
            db.update("INSERT INTO upload_sessions(id,ns,actor,idem,fingerprint,node,parent,name,expected,size,sha256,part_size,object_key,state) VALUES(?,?,?,?,?,?,?,?,?,?,?,?,?,'INITIATED')", id, ns, a.subject(), idem, fp, r.node == null ? UUID.randomUUID() : r.node, r.node == null ? r.parent : node(ns, r.node).get("parent"), r.node == null ? r.name : node(ns, r.node).get("name"), r.expected, r.size, r.sha256, partSize, key);
            return safeUpload(upload(a, ns, id));
        });
    }

    Map<String, Object> upload(Actor a, UUID ns, UUID id) {
        var u = one("SELECT * FROM upload_sessions WHERE ns=? AND id=?", ns, id);
        require(u.get("actor").equals(a.subject()), 403, "Upload owner required");
        return u;
    }

    Map<String, Object> safeUpload(Map<String, Object> u) {
        var v = new LinkedHashMap<>(u);
        if (java.util.Set.of("REVIEW_REQUIRED","BLOCKED").contains(java.util.Objects.toString(u.get("scan_status"),""))) v.put("scanEvidence",db.queryForList("SELECT verdict,covered_bytes,segments,scope,engine FROM blob_scans WHERE object_key=?",u.get("object_key")));
        v.remove("object_key");
        v.remove("multipart");
        v.remove("fingerprint");
        return v;
    }

    void uploadAccess(Actor a, UUID ns, Map<String, Object> u) {
        UUID id = uuid(u.get("node"));
        if (db.queryForList("SELECT id FROM file_nodes WHERE ns=? AND id=?", ns, id).isEmpty())
            parent(a, ns, uuid(u.get("parent")));
        else {
            read(a, ns, id);
            access(a, ns, id, true, false);
        }
    }

    void alive(Map<String, Object> u) {
        require(((Timestamp) u.get("expires")).toInstant().isAfter(Instant.now()), 410, "Upload expired");
        require(List.of("INITIATED", "UPLOADING").contains(u.get("state")), 409, "Upload state disallows operation");
    }

    public Map<String, Object> status(Actor a, UUID ns, UUID id) {
        return locked(a, ns, () -> {
            var u = upload(a, ns, id);
            var result = safeUpload(u);
            if (u.get("state").equals("UPLOADING"))
                result.put("parts", objects.parts(u.get("object_key").toString(), u.get("multipart").toString()));
            return result;
        });
    }

    public Map<String, Object> part(Actor a, UUID ns, UUID id, int number, String sha256) {
        return locked(a, ns, () -> {
            var u = upload(a, ns, id);
            alive(u);
            uploadAccess(a, ns, u);
            checksum(sha256);
            require(number >= 1 && number <= partCount(num(u.get("size")), num(u.get("part_size"))), 400, "Invalid part number");
            String key = u.get("object_key").toString(), multi = (String) u.get("multipart");
            if (multi == null) {
                multi = objects.initiate(key);
                db.update("UPDATE upload_sessions SET multipart=?,state='UPLOADING' WHERE id=?", multi, id);
            }
            db.update("INSERT INTO upload_parts(upload,number,sha256) VALUES(?,?,?) ON CONFLICT(upload,number) DO UPDATE SET sha256=excluded.sha256", id, number, sha256);
            return objects.part(key, multi, number, sha256);
        });
    }

    public Map<String, Object> complete(Actor a, UUID ns, UUID id) {
        // Intent commits before the external side effect; retries/reconciler can recover an ambiguous completion.
        locked(a, ns, () -> {
            var u = upload(a, ns, id);
            require(!u.get("state").equals("QUARANTINED"), 422, "Upload quarantined by scanning policy");
            if (Set.of("COMPLETED", "COMPLETING").contains(u.get("state"))) return null;
            alive(u);
            require(u.get("multipart") != null, 409, "No parts");
            var uploaded = objects.parts(u.get("object_key").toString(), u.get("multipart").toString());
            long partSize = num(u.get("part_size")), size = num(u.get("size"));
            int count = partCount(size, partSize);
            require(uploaded.size() == count, 409, "Missing parts");
            for (int i = 0; i < count; i++) {
                var p = uploaded.get(i);
                long expected = i == count - 1 ? size - i * partSize : partSize;
                require(((Number) p.get("number")).intValue() == i + 1 && num(p.get("size")) == expected, 409, "Invalid part shape");
            }
            db.update("UPDATE upload_sessions SET state='COMPLETING' WHERE id=?", id);
            return null;
        });
        var snapshot = upload(a, ns, id);
        if (snapshot.get("state").equals("COMPLETED")) return read(a, ns, uuid(snapshot.get("node")));
        var lease = db.queryForList("UPDATE upload_sessions SET completion_fence=completion_fence+1,completion_attempts=completion_attempts+1,completion_lease=now()+interval '60 seconds' WHERE id=? AND state='COMPLETING' AND (completion_lease IS NULL OR completion_lease<now()) RETURNING completion_fence", id);
        if (lease.isEmpty()) {
            for(int attempt=0;attempt<600;attempt++) {
                var pending=upload(a,ns,id);
                if(pending.get("state").equals("COMPLETED"))return read(a,ns,uuid(pending.get("node")));
                require(pending.get("state").equals("COMPLETING"),409,"Completion changed state");
                try { Thread.sleep(100); } catch(InterruptedException interrupted){Thread.currentThread().interrupt();throw new IllegalStateException(interrupted);}
            }
            require(false,409,"Completion is already running");
        }
        long fence = num(lease.getFirst().get("completion_fence"));
        try (var heartbeat = new CompletionHeartbeat(db, id, fence)) {
            Map<Integer, String> checks = new HashMap<>();
            for (var p : db.queryForList("SELECT * FROM upload_parts WHERE upload=?", id))
                checks.put(((Number) p.get("number")).intValue(), p.get("sha256").toString());
            String key = snapshot.get("object_key").toString();
            long size = num(snapshot.get("size"));
            objects.complete(key, snapshot.get("multipart").toString(), size, num(snapshot.get("part_size")), checks);
            objects.verify(key, size, snapshot.get("sha256").toString());
            var decision = scans.inspect(objects, key, size);
            Map<String, Object> result = locked(a, ns, () -> {
            var u = upload(a, ns, id);
            require(u.get("state").equals("COMPLETING") && num(u.get("completion_fence")) == fence &&
                ((Timestamp) u.get("completion_lease")).toInstant().isAfter(Instant.now()), 409, "Completion lease lost");
            UUID nodeId = uuid(u.get("node"));
            uploadAccess(a, ns, u);
            var existing = db.queryForList("SELECT * FROM file_nodes WHERE id=?", nodeId);
            if (!existing.isEmpty()) {
                require(visible(ns, nodeId), 409, "Deleted file");
                revision(existing.getFirst(), num(u.get("expected")));
            }
            recordScan(key, size, u.get("sha256").toString(), decision);
            db.update("UPDATE upload_sessions SET scan_status=? WHERE id=?", decision.verdict(), id);
            if (!java.util.Set.of("CLEAN","BYPASSED").contains(decision.verdict())) {
                db.update("UPDATE upload_sessions SET state='QUARANTINED' WHERE id=?", id);
                db.update("UPDATE namespaces SET reserved=reserved-?,quarantined=quarantined+? WHERE id=?", size, size, ns);
                db.update("INSERT INTO audit_records(ns,actor,action,entity) VALUES(?,?,?,?)", ns, a.subject(),decision.verdict().equals("BLOCKED")?"SCAN_BLOCKED":"SCAN_REVIEW_REQUIRED", id);
                return null;
            }
            if (existing.isEmpty())
                db.update("INSERT INTO file_nodes(id,ns,parent,name,kind) VALUES(?,?,?,?,'FILE')", nodeId, ns, u.get("parent"), u.get("name"));
            UUID version = id;
            db.update("INSERT INTO file_versions(id,node,object_key,size,sha256) VALUES(?,?,?,?,?)", version, nodeId, key, size, u.get("sha256"));
            db.update("INSERT INTO processing_jobs(id,ns,node,source) VALUES(?,?,?,?) ON CONFLICT(source) DO NOTHING", UUID.randomUUID(), ns, nodeId, version);
            db.update("UPDATE file_nodes SET current_version=?,revision=revision+1,updated=now() WHERE id=?", version, nodeId);
            consume(ns, size);
            db.update("UPDATE upload_sessions SET state='COMPLETED' WHERE id=?", id);
            event(a, ns, nodeId, "PUBLISH");
            return node(ns, nodeId);
        });
        require(result != null, 422, "Upload quarantined by scanning policy");
        return result;
        } finally {
            db.update("UPDATE upload_sessions SET completion_lease=NULL WHERE id=? AND completion_fence=?", id, fence);
        }
    }

    public Map<String,Object> requestCompletion(Actor a, UUID ns, UUID id) {
        return locked(a, ns, () -> {
            var u=upload(a,ns,id);
            uploadAccess(a,ns,u);
            if (u.get("state").equals("COMPLETED")) return safeUpload(u);
            if (!u.get("state").equals("COMPLETING")) {
                alive(u);
                require(u.get("multipart") != null,409,"No parts");
                db.update("UPDATE upload_sessions SET state='COMPLETING',retry_at=now() WHERE id=?",id);
            }
            return safeUpload(upload(a,ns,id));
        });
    }

    public void abort(Actor a, UUID ns, UUID id) {
        locked(a, ns, () -> {
            var u = upload(a, ns, id);
            if (u.get("state").equals("ABORTED")) return null;
            if (u.get("state").equals("QUARANTINED")) {
                // Quarantine already released its reservation. Abort releases its forensic storage budget once.
                db.update("UPDATE upload_sessions SET state='ABORTED' WHERE id=?", id);
                db.update("UPDATE namespaces SET quarantined=quarantined-? WHERE id=?", u.get("size"), ns);
                db.update("INSERT INTO audit_records(ns,actor,action,entity) VALUES(?,?,'QUARANTINE_RELEASE',?)", ns, a.subject(), id);
                return null;
            }
            require(!u.get("state").equals("COMPLETED"), 409, "Already completed");
            objects.abort(u.get("object_key").toString(), (String) u.get("multipart"));
            db.update("UPDATE upload_sessions SET state='ABORTED' WHERE id=?", id);
            db.update("UPDATE namespaces SET reserved=reserved-? WHERE id=?", u.get("size"), ns);
            return null;
        });
    }

    public Map<String, Object> changes(Actor a, UUID ns, String cursor, int limit) {
        access(a, ns, null, false, true);
        long c = cursor(cursor);
        require(limit > 0 && limit <= 500, 400, "Limit 1..500");
        // One PostgreSQL statement observes floor, head and page in one MVCC snapshot.
        // Readers never wait on a writer's namespace lock or mix pre/post-compaction state.
        var page = db.queryForList("""
                SELECT n.sync_floor,n.seq AS head,c.seq,c.payload,c.created,c.action
                FROM namespaces n LEFT JOIN LATERAL (
                 SELECT seq,payload,created,action FROM change_log WHERE ns=n.id AND seq>? ORDER BY seq LIMIT ?
                ) c ON true WHERE n.id=? ORDER BY c.seq
                """, c, limit, ns);
        require(!page.isEmpty(), 404, "Not found");
        long floor = num(page.getFirst().get("sync_floor"));
        require(c >= floor, 410, "Cursor expired; fetch a new snapshot");
        require(c <= num(page.getFirst().get("head")), 400, "Cursor ahead of stream");
        var rows = new ArrayList<Map<String, Object>>();
        for (var row : page) {
            if (row.get("seq") == null) continue;
            row.remove("sync_floor");
            row.remove("head");
            try {
                row.put("payload", json.readTree(row.get("payload").toString()));
            } catch (Exception e) {
                throw new IllegalStateException(e);
            }
            rows.add(row);
        }
        return Map.of("changes", rows, "cursor", rows.isEmpty() ? c : num(rows.getLast().get("seq")), "minimumCursor", floor);
    }

    public Map<String, Object> snapshot(Actor a, UUID ns, UUID after, int limit) {
        return locked(a, ns, () -> {
            access(a, ns, null, false, true);
            require(limit > 0 && limit <= 500, 400, "Limit 1..500");
            var rows = db.queryForList("SELECT * FROM file_nodes WHERE ns=? AND id>? ORDER BY id LIMIT ?", ns, after, limit);
            return Map.of("nodes", rows, "boundary", namespace(ns).get("seq"), "after", rows.isEmpty() ? after : rows.getLast().get("id"));
        });
    }

    public List<Map<String, Object>> devices(Actor a, UUID ns) {
        access(a, ns, null, false, true);
        return db.queryForList("SELECT * FROM devices WHERE ns=? AND subject=? ORDER BY id", ns, a.subject());
    }

    public UUID device(Actor a, UUID ns, String name) {
        access(a, ns, null, false, true);
        UUID id = UUID.randomUUID();
        db.update("INSERT INTO devices(id,ns,subject,name) VALUES(?,?,?,?)", id, ns, a.subject(), name(name));
        return id;
    }

    public void ack(Actor a, UUID ns, UUID device, long seq) {
        access(a, ns, null, false, true);
        require(seq >= 0 && seq <= num(namespace(ns).get("seq")), 400, "Invalid acknowledgement");
        require(db.update("UPDATE devices SET ack=greatest(ack,?),seen=now() WHERE ns=? AND subject=? AND id=?", seq, ns, a.subject(), device) == 1, 404, "Unknown device");
    }

    public List<Map<String, Object>> shares(Actor a, UUID ns, UUID id) {
        access(a, ns, id, false, true);
        return db.queryForList("SELECT * FROM sharing_permissions WHERE node=?", id);
    }

    public UUID share(Actor a, UUID ns, UUID id, String subject, String role) {
        return locked(a, ns, () -> {
            access(a, ns, id, true, true);
            require(role != null && List.of("VIEWER", "EDITOR").contains(role), 400, "Invalid role");
            require(subject != null && !subject.isBlank() && subject.length()<=255, 400, "Subject required (max 255 characters)");
            UUID grant = UUID.randomUUID();
            db.update("INSERT INTO sharing_permissions(id,node,subject,role) VALUES(?,?,?,?) ON CONFLICT(node,subject) DO UPDATE SET role=excluded.role", grant, id, subject, role);
            event(a, ns, id, "SHARE");
            return uuid(one("SELECT id FROM sharing_permissions WHERE node=? AND subject=?", id, subject).get("id"));
        });
    }

    public void revoke(Actor a, UUID ns, UUID id, UUID grant) {
        locked(a, ns, () -> {
            access(a, ns, id, true, true);
            db.update("DELETE FROM sharing_permissions WHERE node=? AND id=?", id, grant);
            db.update("UPDATE share_links SET revoked=true WHERE node=? AND id=?", id, grant);
            event(a, ns, id, "REVOKE");
            return null;
        });
    }

    public Map<String, Object> link(Actor a, UUID ns, UUID id, long seconds) {
        return locked(a, ns, () -> {
            read(a, ns, id);
            access(a, ns, id, true, true);
            require(seconds > 0 && seconds <= 86400, 400, "TTL 1..86400");
            byte[] random = new byte[32];
            new java.security.SecureRandom().nextBytes(random);
            String token = Base64.getUrlEncoder().withoutPadding().encodeToString(random);
            UUID link = UUID.randomUUID();
            db.update("INSERT INTO share_links(id,node,token_hash,expires) VALUES(?,?,?,?)", link, id, hash(token), Timestamp.from(Instant.now().plusSeconds(seconds)));
            return Map.of("id", link, "token", token);
        });
    }

    public Map<String, Object> publicDownload(String token) {
        var l = one("SELECT l.node,n.ns FROM share_links l JOIN file_nodes n ON n.id=l.node WHERE token_hash=? AND NOT revoked AND expires>now()", hash(token));
        UUID ns = uuid(l.get("ns"));
        var n = namespace(ns);
        // Recheck link under the same namespace lock as revocation.
        return locked(new Actor(n.get("tenant").toString(), n.get("subject").toString()), ns, () -> {
            one("SELECT id FROM share_links WHERE token_hash=? AND NOT revoked AND expires>now()", hash(token));
            return download(new Actor(n.get("tenant").toString(), n.get("subject").toString()), ns, uuid(l.get("node")), null);
        });
    }

    public Map<String, Object> copy(Actor a, UUID ns, UUID id, UUID parent, String name) {
        return locked(a, ns, () -> {
            var source = read(a, ns, id);
            parent(a, ns, parent);
            require(source.get("kind").equals("FILE"), 400, "Folder copy requires an asynchronous subtree job");
            var v = one("SELECT * FROM file_versions WHERE id=?", source.get("current_version"));
            scanAccess(v);
            long size = num(v.get("size"));
            reserve(ns, size);
            UUID next = UUID.randomUUID(), version = UUID.randomUUID();
            db.update("INSERT INTO file_nodes(id,ns,parent,name,kind) VALUES(?,?,?,?,'FILE')", next, ns, parent, name(name));
            db.update("INSERT INTO file_versions(id,node,object_key,size,sha256) VALUES(?,?,?,?,?)", version, next, v.get("object_key"), size, v.get("sha256"));
            db.update("UPDATE file_nodes SET current_version=? WHERE id=?", version, next);
            consume(ns, size);
            event(a, ns, next, "COPY");
            return node(ns, next);
        });
    }

    public int retain(Actor a, UUID ns, UUID id, int keep) {
        return locked(a, ns, () -> {
            access(a, ns, id, true, true);
            require(keep >= 1 && keep <= 10000, 400, "Keep 1..10000 versions");
            node(ns, id);
            var removed = db.queryForList("DELETE FROM file_versions WHERE id IN (SELECT id FROM file_versions WHERE node=? AND id<>(SELECT current_version FROM file_nodes WHERE id=?) ORDER BY created DESC,id DESC OFFSET ?) RETURNING size", id, id, keep - 1);
            long freed = removed.stream().mapToLong(v -> num(v.get("size"))).sum();
            db.update("UPDATE namespaces SET used=used-? WHERE id=?", freed, ns);
            event(a, ns, id, "RETENTION");
            return removed.size();
        });
    }

    public record Actor(String tenant, String subject) {
    }

    public record UploadRequest(UUID parent, String name, UUID node, long expected, long size, String sha256) {
    }

}
