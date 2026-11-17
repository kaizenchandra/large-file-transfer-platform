package com.example.drive;

import tools.jackson.databind.JsonNode;
import org.springframework.stereotype.Service;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static com.example.drive.Rules.cursor;
import static com.example.drive.Rules.require;

/**
 * Coalesced current-state streams. Namespace locking serializes refresh with ACL/hierarchy changes.
 */
@Service
public class SharedSync {
    private final Drive drive;

    public SharedSync(Drive drive) {
        this.drive = drive;
    }

    public Map<String, Object> create(Drive.Actor actor, UUID ns, UUID root) {
        return drive.locked(actor, ns, () -> {
            require(drive.read(actor, ns, root).get("kind").equals("FOLDER"), 400, "Shared root must be a folder");
            drive.db.update("INSERT INTO sync_subscriptions(id,ns,root,subject) VALUES(?,?,?,?) ON CONFLICT(ns,root,subject) DO NOTHING",
                    UUID.randomUUID(), ns, root, actor.subject());
            return drive.one("SELECT id,ns,root FROM sync_subscriptions WHERE ns=? AND root=? AND subject=?", ns, root, actor.subject());
        });
    }

    private Map<String, Object> subscription(Drive.Actor actor, UUID id) {
        // Bind both tenant and subject before exposing even the namespace/root identifiers.
        return drive.one("SELECT s.* FROM sync_subscriptions s JOIN namespaces n ON n.id=s.ns WHERE s.id=? AND s.subject=? AND n.tenant=?",
                id, actor.subject(), actor.tenant());
    }

    public Map<String, Object> read(Drive.Actor actor, UUID id) {
        var sub = subscription(actor, id);
        return drive.locked(actor, (UUID) sub.get("ns"), () -> {
            drive.access(actor, (UUID) sub.get("ns"), (UUID) sub.get("root"), false, false);
            return Map.of("id", id, "ns", sub.get("ns"), "root", sub.get("root"));
        });
    }

    public void remove(Drive.Actor actor, UUID id) {
        var sub = subscription(actor, id);
        drive.locked(actor, (UUID) sub.get("ns"), () -> {
            // A recipient can release its stream storage even after its grant was revoked.
            drive.db.update("DELETE FROM shared_changes WHERE subscription=?", id);
            drive.db.update("DELETE FROM sync_projection WHERE subscription=?", id);
            drive.db.update("DELETE FROM sync_subscriptions WHERE id=?", id);
            return null;
        });
    }

    private JsonNode parse(Object value) {
        try {
            return drive.json.readTree(value.toString());
        } catch (tools.jackson.core.JacksonException e) {
            throw new IllegalStateException(e);
        }
    }

    private void append(UUID id, JsonNode payload, String action) {
        long seq = drive.num(drive.one("UPDATE sync_subscriptions SET seq=seq+1 WHERE id=? RETURNING seq", id).get("seq"));
        drive.db.update("INSERT INTO shared_changes(subscription,seq,payload,action) VALUES(?,?,?::jsonb,?)", id, seq, payload.toString(), action);
    }

    public Map<String, Object> changes(Drive.Actor actor, UUID id, String cursor, int limit) {
        long c = cursor(cursor);
        require(limit > 0 && limit <= 500, 400, "Limit 1..500");
        var sub = subscription(actor, id);
        UUID ns = (UUID) sub.get("ns"), root = (UUID) sub.get("root");
        return drive.locked(actor, ns, () -> {
            drive.access(actor, ns, root, false, false); // Deny revoked roots before reading any history.
            require(c <= drive.num(drive.one("SELECT seq FROM sync_subscriptions WHERE id=?", id).get("seq")), 400, "Cursor ahead of stream");
            List<Map<String, Object>> current = drive.visible(ns, root) ? drive.db.queryForList("""
                    WITH RECURSIVE tree AS (
                     SELECT * FROM file_nodes WHERE ns=? AND id=? AND NOT deleted
                     UNION ALL SELECT n.* FROM file_nodes n JOIN tree p ON n.parent=p.id WHERE n.ns=? AND NOT n.deleted
                    ) SELECT * FROM tree ORDER BY id
                    """, ns, root, ns) : List.of();
            var previous = new LinkedHashMap<UUID, JsonNode>();
            for (var row : drive.db.queryForList("SELECT node,payload FROM sync_projection WHERE subscription=? ORDER BY node", id))
                previous.put((UUID) row.get("node"), parse(row.get("payload")));
            for (var row : current) {
                UUID node = (UUID) row.get("id");
                if (node.equals(root)) row.put("parent", null); // Never expose private ancestor IDs/paths.
                // Round-trip both sides through JSON: JDBC longs otherwise differ from parsed small integers.
                JsonNode payload = parse(drive.json.valueToTree(row).toString()), old = previous.remove(node);
                if (!payload.equals(old)) {
                    append(id, payload, "UPSERT");
                    drive.db.update("INSERT INTO sync_projection(subscription,node,payload) VALUES(?,?,?::jsonb) ON CONFLICT(subscription,node) DO UPDATE SET payload=excluded.payload", id, node, payload.toString());
                }
            }
            for (var entry : previous.entrySet()) {
                var tombstone = entry.getValue().deepCopy();
                ((tools.jackson.databind.node.ObjectNode) tombstone).put("deleted", true);
                append(id, tombstone, "REMOVE");
                drive.db.update("DELETE FROM sync_projection WHERE subscription=? AND node=?", id, entry.getKey());
            }
            var rows = drive.db.queryForList("SELECT seq,payload,action,created FROM shared_changes WHERE subscription=? AND seq>? ORDER BY seq LIMIT ?", id, c, limit);
            for (var row : rows) row.put("payload", parse(row.get("payload")));
            return Map.of("changes", rows, "cursor", rows.isEmpty() ? c : drive.num(rows.getLast().get("seq")));
        });
    }
}
