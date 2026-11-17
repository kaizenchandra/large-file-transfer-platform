import tools.jackson.databind.*;
import tools.jackson.databind.node.*;

import java.nio.file.*;
import java.net.*;
import java.net.http.*;
import java.io.*;
import java.security.*;
import java.time.Duration;
import java.util.*;

/**
 * Single-process synchronization client with an optional durable offline write journal.
 */
public class SyncClient {
    final ObjectMapper json = new ObjectMapper();
    final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build();
    final boolean bidirectional;
    final String api, auth, subscription;
    final Path root, stateFile;
    final Set<String> rejectedDirectoryDeletes = new HashSet<>();
    boolean releaseQuarantine;
    String sharedNamespace;
    ObjectNode state;

    SyncClient(String api, Path root, boolean bidirectional) throws Exception {
        this(api, root, bidirectional, null);
    }

    SyncClient(String api, Path root, boolean bidirectional, String subscription) throws Exception {
        if (bidirectional && subscription != null) throw new IllegalArgumentException("Shared mirrors are pull-only");
        this.subscription = subscription;
        this.bidirectional = bidirectional;
        this.api = api;
        this.root = root.toAbsolutePath().normalize();
        Files.createDirectories(this.root);
        if (Files.isSymbolicLink(this.root)) throw new IOException("Symlink root rejected");
        auth = Objects.requireNonNull(System.getenv("DRIVE_AUTH"), "DRIVE_AUTH must contain Bearer ... or Basic ...");
        stateFile = this.root.resolve(".drive-state.json");
        state = Files.exists(stateFile) ? (ObjectNode) json.readTree(stateFile.toFile()) : json.createObjectNode();
        if (!state.has("nodes")) state.set("nodes", json.createObjectNode());
        if (!state.has("files")) state.set("files", json.createObjectNode());
        if (!state.has("directories")) state.set("directories", json.createArrayNode());
    }

    static String conflictName(String name, String suffix) {
        // Keep within common filesystem UTF-8 byte limits as well as the backend character limit.
        int end = 0, bytes = suffix.getBytes(java.nio.charset.StandardCharsets.UTF_8).length;
        while (end < name.length()) {
            int cp = name.codePointAt(end), length = new String(Character.toChars(cp)).getBytes(java.nio.charset.StandardCharsets.UTF_8).length;
            if (bytes + length > 255) break;
            bytes += length;
            end += Character.charCount(cp);
        }
        return name.substring(0, end) + suffix;
    }

    public static void main(String[] args) throws Exception {
        if (args.length < 2)
            throw new IllegalArgumentException("Usage: SyncClient.java API_ROOT LOCAL_DIRECTORY [--once] [--bidirectional] [--release-quarantine] [--shared=SUBSCRIPTION_UUID]");
        var options = new HashSet<>(Arrays.asList(args).subList(2, args.length));
        String subscription = null;
        for (String option : options) {
            if (option.startsWith("--shared=")) {
                if (subscription != null) throw new IllegalArgumentException("One shared subscription required");
                subscription = UUID.fromString(option.substring("--shared=".length())).toString();
            } else if (!Set.of("--once", "--bidirectional", "--release-quarantine").contains(option))
                throw new IllegalArgumentException("Unknown option");
        }
        boolean once = options.contains("--once");
        var client = new SyncClient(args[0], Path.of(args[1]), options.contains("--bidirectional"), subscription);
        if (options.contains("--release-quarantine") && !options.contains("--bidirectional"))
            throw new IllegalArgumentException("Quarantine recovery requires --bidirectional");
        client.releaseQuarantine = options.contains("--release-quarantine");
        // File lock prevents two clients overwriting the same state.
        try (var channel = java.nio.channels.FileChannel.open(client.root.resolve(".drive-lock"), StandardOpenOption.CREATE, StandardOpenOption.WRITE); var lock = channel.tryLock()) {
            if (lock == null) throw new IOException("Mirror already running");
            do {
                try {
                    client.once();
                } catch (Exception e) {
                    System.err.println("Sync retry: " + e.getClass().getSimpleName());
                    if (once) throw e;
                }
                if (once) break;
                Thread.sleep(2000);
            } while (true);
        }
    }

    static boolean inside(String path, String ancestor) {
        return path.equals(ancestor) || path.startsWith(ancestor + "/");
    }

    JsonNode get(String path) throws Exception {
        if (subscription != null && path.startsWith("/sync/changes?"))
            path = "/sync/subscriptions/" + subscription + "/changes" + path.substring(path.indexOf('?'));
        if (sharedNamespace != null && path.startsWith("/files/"))
            path += (path.contains("?") ? "&" : "?") + "ns=" + sharedNamespace;
        var response = http.send(HttpRequest.newBuilder(URI.create(api + path)).timeout(Duration.ofSeconds(60)).header("Authorization", auth).GET().build(), HttpResponse.BodyHandlers.ofString());
        if (response.statusCode() == 410 && path.startsWith("/sync/changes")) throw new CursorExpired();
        if (response.statusCode() != 200) throw new ApiFailure(response.statusCode());
        return json.readTree(response.body());
    }

    JsonNode write(String method, String path, JsonNode body, String key) throws Exception {
        var request = HttpRequest.newBuilder(URI.create(api + path)).timeout(Duration.ofMinutes(15))
                .header("Authorization", auth).header("Content-Type", "application/json");
        if (key != null) request.header("Idempotency-Key", key);
        var response = http.send(request.method(method, HttpRequest.BodyPublishers.ofString(body.toString())).build(), HttpResponse.BodyHandlers.ofString());
        if (response.statusCode() < 200 || response.statusCode() >= 300) throw new ApiFailure(response.statusCode());
        return response.body().isBlank() ? json.nullNode() : json.readTree(response.body());
    }

    void save() throws Exception {
        Path temp = Files.createTempFile(root, ".drive-state-", ".tmp");
        try {
            json.writeValue(temp.toFile(), state);
            try (var channel = java.nio.channels.FileChannel.open(temp, StandardOpenOption.WRITE)) {
                channel.force(true);
            }
            Files.move(temp, stateFile, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
        } finally {
            Files.deleteIfExists(temp);
        }
    }

    ObjectNode nodes() {
        return (ObjectNode) state.get("nodes");
    }

    ObjectNode files() {
        return (ObjectNode) state.get("files");
    }

    void journal(ObjectNode operation) throws Exception {
        state.set("pending", operation);
        save();
    }

    ObjectNode operation(String kind, String relative) {
        return json.createObjectNode().put("kind", kind).put("path", relative).put("key", UUID.randomUUID().toString());
    }

    void queueUpload(String relative, String id, JsonNode baseline, String parent) throws Exception {
        Path source = safe(relative), spool = Files.createTempFile(root, ".drive-upload-", ".bin");
        boolean journaled = false;
        try {
            Files.copy(source, spool, StandardCopyOption.REPLACE_EXISTING);
            String checksum = hash(spool);
            if (!checksum.equals(hash(safe(relative)))) throw new IOException("Source changed while staging; retry");
            try (var channel = java.nio.channels.FileChannel.open(spool, StandardOpenOption.WRITE)) {
                channel.force(true);
            }
            ObjectNode op = operation("upload", relative);
            op.put("spool", spool.getFileName().toString()).put("hash", checksum);
            ObjectNode request = json.createObjectNode().put("expected", 0).put("name", source.getFileName().toString()).put("size", Files.size(spool)).put("sha256", checksum);
            if (parent == null) request.putNull("parent");
            else request.put("parent", parent);
            if (id != null) {
                request.put("node", id).put("expected", baseline.path("revision").asLong());
            }
            op.set("request", request);
            journal(op);
            journaled = true;
        } finally {
            if (!journaled) Files.deleteIfExists(spool);
        }
    }

    Path spool(JsonNode op) throws Exception {
        String name = op.path("spool").asText();
        if (!name.startsWith(".drive-upload-") || !name.endsWith(".bin") || !Path.of(name).getFileName().toString().equals(name))
            throw new IOException("Invalid upload spool");
        Path p = root.resolve(name);
        if (Files.isSymbolicLink(p) || !Files.isRegularFile(p, LinkOption.NOFOLLOW_LINKS))
            throw new IOException("Missing upload spool");
        return p;
    }

    // Each subscription opens only the selected range; no entire part/file buffer enters the heap.
    HttpRequest.BodyPublisher chunk(Path source, long offset, long size) {
        if (size == 0) return HttpRequest.BodyPublishers.noBody();
        return HttpRequest.BodyPublishers.fromPublisher(HttpRequest.BodyPublishers.ofInputStream(() -> {
            try {
                var channel = java.nio.channels.FileChannel.open(source, StandardOpenOption.READ);
                channel.position(offset);
                return new FilterInputStream(java.nio.channels.Channels.newInputStream(channel)) {
                    long remaining = size;

                    @Override
                    public int read() throws IOException {
                        if (remaining == 0) return -1;
                        int b = super.read();
                        if (b >= 0) remaining--;
                        return b;
                    }

                    @Override
                    public int read(byte[] b, int off, int len) throws IOException {
                        if (remaining == 0) return -1;
                        int n = in.read(b, off, (int) Math.min(remaining, len));
                        if (n > 0) remaining -= n;
                        return n;
                    }
                };
            } catch (IOException e) {
                throw new UncheckedIOException(e);
            }
        }), size);
    }

    String chunkHash(Path source, long offset, long size) throws Exception {
        var digest = MessageDigest.getInstance("SHA-256");
        try (var channel = java.nio.channels.FileChannel.open(source, StandardOpenOption.READ)) {
            channel.position(offset);
            var buffer = java.nio.ByteBuffer.allocate(131072);
            long remaining = size;
            while (remaining > 0) {
                buffer.clear().limit((int) Math.min(buffer.capacity(), remaining));
                int n = channel.read(buffer);
                if (n < 0) throw new EOFException();
                digest.update(buffer.array(), 0, n);
                remaining -= n;
            }
        }
        return Base64.getEncoder().encodeToString(digest.digest());
    }

    JsonNode sendUpload(ObjectNode op) throws Exception {
        Path staged = spool(op);
        JsonNode request = op.get("request");
        if (Files.size(staged) != request.path("size").asLong() || !hash(staged).equals(op.path("hash").asText()))
            throw new IOException("Staged content integrity mismatch");
        // Replaying initiation with its original key also recovers a lost session-creation response.
        JsonNode session = write("POST", "/uploads", request, op.path("key").asText());
        String id = session.path("id").asText();
        op.put("upload", id);
        save();
        JsonNode status = get("/uploads/" + id);
        String phase = status.path("state").asText();
        if (phase.equals("QUARANTINED"))
            throw new ApiFailure(status.path("completion_failure_status").asInt(422)); // Preserve staged bytes unless the failure contract identifies a recoverable logical conflict.
        if (phase.equals("ABORTED")) {
            op.remove("upload");
            op.put("key", UUID.randomUUID().toString());
            save();
            throw new IOException("Aborted upload reset; retry staged operation");
        }
        if (!phase.equals("COMPLETING") && !phase.equals("COMPLETED")) {
            long total = request.path("size").asLong(), partSize = session.path("part_size").asLong();
            Map<Integer, String> uploaded = new HashMap<>();
            for (JsonNode p : status.path("parts")) uploaded.put(p.path("number").asInt(), p.path("checksum").asText());
            int count = (int) Math.max(1, (total + partSize - 1) / partSize);
            for (int number = 1; number <= count; number++) {
                long offset = (number - 1) * partSize, size = Math.min(partSize, total - offset);
                String checksum = chunkHash(staged, offset, size);
                if (checksum.equals(uploaded.get(number))) continue;
                var signed = write("POST", "/uploads/" + id + "/parts", json.createObjectNode().put("number", number).put("sha256", checksum), null);
                var builder = HttpRequest.newBuilder(URI.create(signed.path("url").asText())).timeout(Duration.ofMinutes(30));
                signed.path("headers").properties().iterator().forEachRemaining(header -> {
                    if (!header.getKey().equalsIgnoreCase("host") && !header.getKey().equalsIgnoreCase("content-length"))
                        for (JsonNode value : header.getValue()) builder.header(header.getKey(), value.asText());
                });
                var response = http.send(builder.PUT(chunk(staged, offset, size)).build(), HttpResponse.BodyHandlers.discarding());
                if (response.statusCode() != 200)
                    throw new IOException("Part transfer status " + response.statusCode());
            }
        }
        write("POST", "/uploads/" + id + "/complete", json.createObjectNode(), null);
        for (int attempt=0; attempt<900; attempt++) {
            status = get("/uploads/" + id);
            if (status.path("state").asText().equals("COMPLETED")) return get("/files/" + status.path("node").asText());
            if (!status.path("state").asText().equals("COMPLETING")) throw new ApiFailure(status.path("completion_failure_status").asInt(422));
            Thread.sleep(1000);
        }
        throw new IOException("Completion still pending; retry same session");
    }

    JsonNode findFolder(String parent, String name) throws Exception {
        String after = "00000000-0000-0000-0000-000000000000";
        while (true) {
            JsonNode page = get("/files?" + (parent == null ? "" : "parent=" + parent + "&") + "q=" + URLEncoder.encode(name, java.nio.charset.StandardCharsets.UTF_8) + "&after=" + after + "&limit=500");
            for (JsonNode n : page)
                if (n.path("name").asText().equals(name) && n.path("kind").asText().equals("FOLDER")) return n;
            if (page.size() < 500) return null;
            after = page.get(page.size() - 1).path("id").asText();
        }
    }

    void releasePendingQuarantine() throws Exception {
        JsonNode pending = state.get("pending");
        if (pending == null || !pending.path("kind").asText().equals("upload") || !pending.has("upload"))
            throw new IOException("No pending quarantined content upload");
        ObjectNode op = (ObjectNode) pending;
        String upload = UUID.fromString(op.path("upload").asText()).toString();
        JsonNode remote = get("/uploads/" + upload);
        if (!remote.path("scan_status").asText().equals("BLOCKED") || !Set.of("QUARANTINED", "ABORTED").contains(remote.path("state").asText()))
            throw new IOException("Only an unpublished quarantined upload can be released");
        op.put("releasingQuarantine", true);
        save();
        if (remote.path("state").asText().equals("QUARANTINED"))
            write("POST", "/uploads/" + upload + "/abort", json.createObjectNode(), null);
        Path recovery = root.resolve(".drive-rejected-" + upload + ".bin");
        if (Files.exists(recovery, LinkOption.NOFOLLOW_LINKS)) {
            if (!Files.isRegularFile(recovery, LinkOption.NOFOLLOW_LINKS) || !hash(recovery).equals(op.path("hash").asText()))
                throw new IOException("Quarantine recovery file does not match staged bytes");
        } else {
            Path staged = spool(op);
            if (!hash(staged).equals(op.path("hash").asText()))
                throw new IOException("Staged quarantine integrity mismatch");
            Files.move(staged, recovery, StandardCopyOption.ATOMIC_MOVE);
        }
        // Never delete rejected local bytes or allow the next scan to upload them again automatically.
        Path original = safe(op.path("path").asText());
        if (Files.isRegularFile(original, LinkOption.NOFOLLOW_LINKS) && hash(original).equals(op.path("hash").asText()))
            Files.move(original, root.resolve(".drive-rejected-source-" + UUID.randomUUID() + ".bin"), StandardCopyOption.ATOMIC_MOVE);
        // Removing rejected working bytes is recovery, not an intention to delete the last safe remote version.
        if (!Files.exists(original, LinkOption.NOFOLLOW_LINKS) && op.path("request").has("node"))
            files().remove(op.path("request").path("node").asText());
        state.remove("pending");
        save();
        System.err.println("Quarantine released; rejected bytes retained in reserved local recovery files");
    }

    void runPending() throws Exception {
        if (!state.has("pending")) return;
        ObjectNode op = (ObjectNode) state.get("pending");
        String kind = op.path("kind").asText();
        if (kind.equals("folder")) {
            JsonNode n;
            try {
                n = write("POST", "/folders", op.get("request"), op.path("key").asText());
            } catch (ApiFailure e) {
                if (e.status != 409) throw e;
                JsonNode request = op.get("request");
                n = findFolder(request.path("parent").isNull() ? null : request.path("parent").asText(), request.path("name").asText());
                if (n == null) {
                    state.remove("pending");
                    save();
                    throw new IOException("Directory name collision; rename the local directory");
                }
            }
            String id = n.path("id").asText();
            nodes().set(id, n);
            recordFolder(id, op.path("path").asText());
            markFolder(op.path("path").asText(), id);
        } else if (kind.equals("folderRelocate")) {
            String id = op.path("id").asText(), old = op.path("oldPath").asText();
            try {
                JsonNode n = write("PATCH", "/files/" + id, op.get("request"), op.path("key").asText());
                nodes().set(id, n);
                rebaseFolder(old, op.path("path").asText());
                ((ObjectNode) folders().get(id)).put("revision", n.path("revision").asLong());
            } catch (ApiFailure e) {
                if (!Set.of(404, 409, 412).contains(e.status)) throw e;
                forgetFolderPaths(old);
                System.err.println("Sync conflict: preserving relocated directory as a separate tree");
            }
        } else if (kind.equals("folderDelete")) {
            String relative = op.path("path").asText();
            try {
                JsonNode n = write("POST", "/folders/" + op.path("id").asText() + "/delete-safe", op.get("request"), op.path("key").asText());
                nodes().set(n.path("id").asText(), n);
                forgetFolderPaths(relative);
            } catch (ApiFailure e) {
                if (e.status != 404 && e.status != 412) throw e;
                rejectedDirectoryDeletes.add(relative);
                System.err.println("Sync conflict: retaining changed remote directory");
            }
        } else if (kind.equals("delete")) {
            try {
                JsonNode n = write("PATCH", "/files/" + op.path("id").asText(), op.get("request"), op.path("key").asText());
                nodes().set(n.path("id").asText(), n);
            } catch (ApiFailure e) {
                if (e.status != 404 && e.status != 412) throw e;
                // A newer remote revision wins over a stale local delete; pull restores it.
                System.err.println("Sync conflict: remote revision retained after offline deletion");
            }
            files().remove(op.path("id").asText());
        } else if (kind.equals("relocate")) {
            String id = op.path("id").asText();
            try {
                JsonNode n = write("PATCH", "/files/" + id, op.get("request"), op.path("key").asText());
                nodes().set(id, n);
                ObjectNode tracked = (ObjectNode) files().get(id);
                tracked.put("path", op.path("path").asText()).put("revision", n.path("revision").asLong());
            } catch (ApiFailure e) {
                if (!Set.of(404, 409, 412).contains(e.status)) throw e;
                // Keep the renamed local file as a new upload; pull restores any newer remote original.
                files().remove(id);
                System.err.println("Sync conflict: preserving local relocation as a new file");
            }
        } else if (kind.equals("upload")) {
            JsonNode n;
            try {
                n = sendUpload(op);
            } catch (ApiFailure e) {
                if (e.status == 410) {
                    if (op.has("upload"))
                        write("POST", "/uploads/" + op.path("upload").asText() + "/abort", json.createObjectNode(), null);
                    op.remove("upload");
                    op.put("key", UUID.randomUUID().toString());
                    save();
                    throw new IOException("Expired upload reset; retry staged operation");
                }
                if (!Set.of(404, 409, 412).contains(e.status)) throw e;
                if (op.has("upload"))
                    write("POST", "/uploads/" + op.path("upload").asText() + "/abort", json.createObjectNode(), null);
                if (op.path("conflict").asBoolean()) {
                    op.remove("upload");
                    op.put("key", UUID.randomUUID().toString());
                    save();
                    throw new IOException("Conflict upload could not publish; staged bytes retained");
                }
                // Preserve opaque binaries as a new root file, even when the former parent was deleted.
                ObjectNode request = (ObjectNode) op.get("request");
                String name = conflictName(request.path("name").asText(), ".conflict-" + UUID.randomUUID());
                request.remove("node");
                request.put("expected", 0).putNull("parent");
                request.put("name", name);
                op.put("conflict", true).put("key", UUID.randomUUID().toString());
                op.remove("upload");
                save();
                System.err.println("Sync conflict: publishing preserved local content as a separate file");
                n = sendUpload(op);
            }
            String target = op.path("path").asText();
            if (op.path("conflict").asBoolean()) {
                target = op.path("request").path("name").asText();
                Path original = safe(op.path("path").asText());
                if (Files.isRegularFile(original, LinkOption.NOFOLLOW_LINKS) && hash(original).equals(op.path("hash").asText()) && !Files.exists(safe(target)))
                    Files.move(original, safe(target), StandardCopyOption.ATOMIC_MOVE);
            }
            String id = n.path("id").asText();
            nodes().set(id, n);
            // The completion response may describe a newer revision. Track exactly OUR immutable version.
            ObjectNode tracked = json.createObjectNode().put("path", target).put("hash", op.path("hash").asText()).put("version", op.path("upload").asText());
            tracked.put("revision", op.path("request").has("node") ? op.path("request").path("expected").asLong() + 1 : 2);
            files().set(id, tracked);
        } else throw new IOException("Unknown journal operation");
        String staged = op.path("spool").asText();
        state.remove("pending");
        save();
        if (!staged.isEmpty()) Files.deleteIfExists(root.resolve(staged));
    }

    ObjectNode folders() {
        if (!state.has("folderViews")) state.set("folderViews", json.createObjectNode());
        return (ObjectNode) state.get("folderViews");
    }

    String subtreeHash(String rootId) throws Exception {
        Map<String, List<String>> children = new HashMap<>();
        nodes().properties().iterator().forEachRemaining(e -> children.computeIfAbsent(e.getValue().path("parent").asText(), k -> new ArrayList<>()).add(e.getKey()));
        var ids = new TreeSet<String>();
        var queue = new ArrayDeque<String>();
        queue.add(rootId);
        while (!queue.isEmpty()) {
            String id = queue.removeFirst();
            if (!ids.add(id)) throw new IOException("Cycle in subtree");
            queue.addAll(children.getOrDefault(id, List.of()));
        }
        var digest = MessageDigest.getInstance("SHA-256");
        for (String id : ids)
            digest.update((id + ":" + nodes().path(id).path("revision").asLong() + "\n").getBytes(java.nio.charset.StandardCharsets.UTF_8));
        return Base64.getEncoder().encodeToString(digest.digest());
    }

    void recordFolder(String id, String relative) throws Exception {
        folders().set(id, json.createObjectNode().put("path", relative).put("revision", nodes().path(id).path("revision").asLong()).put("subtreeHash", subtreeHash(id)));
    }

    void upgradeFolderViews() throws Exception {
        if (state.has("folderViews")) return;
        Set<String> materialized = new HashSet<>();
        state.path("directories").forEach(p -> materialized.add(p.asText()));
        folders();
        var ids = new ArrayList<String>();
        nodes().propertyNames().iterator().forEachRemaining(ids::add);
        for (String id : ids)
            if (nodes().path(id).path("kind").asText().equals("FOLDER")) {
                String relative = path(id, new HashSet<>());
                if (relative != null && materialized.contains(relative)) recordFolder(id, relative);
            }
    }

    String folderMarker(Path directory) throws Exception {
        Path marker = directory.resolve(".drive-folder-id");
        if (!Files.exists(marker, LinkOption.NOFOLLOW_LINKS)) return null;
        if (!Files.isRegularFile(marker, LinkOption.NOFOLLOW_LINKS) || Files.size(marker) > 1024)
            throw new IOException("Invalid directory marker");
        JsonNode identity = json.readTree(marker.toFile());
        if (!identity.path("namespace").asText().equals(state.path("namespace").asText()) ||
                !identity.path("subscription").asText("").equals(state.path("subscription").asText(""))) return null;
        return UUID.fromString(identity.path("id").asText()).toString();
    }

    void markFolder(String relative, String id) throws Exception {
        Path directory = safe(relative), marker = directory.resolve(".drive-folder-id");
        if (Files.isSymbolicLink(marker)) throw new IOException("Symlink marker rejected");
        var identity = json.createObjectNode().put("namespace", state.path("namespace").asText()).put("subscription", state.path("subscription").asText("")).put("id", id);
        Path temp = Files.createTempFile(directory, ".drive-marker-", ".tmp");
        try {
            json.writeValue(temp.toFile(), identity);
            try (var channel = java.nio.channels.FileChannel.open(temp, StandardOpenOption.WRITE)) {
                channel.force(true);
            }
            Files.move(temp, marker, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
        } finally {
            Files.deleteIfExists(temp);
        }
    }

    void rebaseFolder(String oldPath, String newPath) {
        for (JsonNode entry : files())
            if (inside(entry.path("path").asText(), oldPath))
                ((ObjectNode) entry).put("path", newPath + entry.path("path").asText().substring(oldPath.length()));
        for (JsonNode entry : folders())
            if (inside(entry.path("path").asText(), oldPath))
                ((ObjectNode) entry).put("path", newPath + entry.path("path").asText().substring(oldPath.length()));
    }

    void forgetFolderPaths(String oldPath) {
        for (ObjectNode entries : List.of(files(), folders())) {
            var remove = new ArrayList<String>();
            entries.properties().iterator().forEachRemaining(e -> {
                if (inside(e.getValue().path("path").asText(), oldPath)) remove.add(e.getKey());
            });
            remove.forEach(entries::remove);
        }
    }

    String ensureDirectory(String relative) throws Exception {
        if (relative == null || relative.isEmpty()) return null;
        safe(relative);
        var ids = new ArrayList<String>();
        nodes().propertyNames().iterator().forEachRemaining(ids::add);
        for (String id : ids)
            if (nodes().path(id).path("kind").asText().equals("FOLDER") && relative.equals(path(id, new HashSet<>())))
                return id;
        Path local = Path.of(relative);
        String parent = ensureDirectory(local.getParent() == null ? null : local.getParent().toString());
        JsonNode match = findFolder(parent, local.getFileName().toString());
        if (match == null) {
            ObjectNode request = json.createObjectNode().put("name", local.getFileName().toString());
            if (parent == null) request.putNull("parent");
            else request.put("parent", parent);
            ObjectNode op = operation("folder", relative);
            op.set("request", request);
            journal(op);
            runPending();
        } else {
            String id = match.path("id").asText();
            nodes().set(id, match);
            recordFolder(id, relative);
            markFolder(relative, id);
        }
        ids.clear();
        nodes().propertyNames().iterator().forEachRemaining(ids::add);
        for (String id : ids)
            if (nodes().path(id).path("kind").asText().equals("FOLDER") && relative.equals(path(id, new HashSet<>())))
                return id;
        throw new IOException("Directory creation did not produce a mapped parent");
    }

    void pushFolders(List<Path> paths) throws Exception {
        Map<String, List<String>> identities = new HashMap<>();
        for (Path p : paths)
            if (Files.isDirectory(p, LinkOption.NOFOLLOW_LINKS)) {
                String id = folderMarker(p);
                if (id != null)
                    identities.computeIfAbsent(id, k -> new ArrayList<>()).add(root.relativize(p).toString());
            }
        // Parents are considered before descendants; a successful move rebases every tracked child.
        for (Path p : paths)
            if (Files.isDirectory(p, LinkOption.NOFOLLOW_LINKS)) {
                String id = folderMarker(p), target = root.relativize(p).toString();
                if (id == null || !folders().has(id) || identities.get(id).size() != 1) continue;
                JsonNode baseline = folders().get(id);
                String old = baseline.path("path").asText();
                if (old.equals(target)) continue;
                if (Files.exists(safe(old), LinkOption.NOFOLLOW_LINKS)) {
                    for (JsonNode other : folders())
                        if (other.path("path").asText().equals(target))
                            throw new IOException("Overlapping directory identities; resolve the local swap before synchronization");
                    continue;
                }
                String parent = ensureDirectory(p.getParent().equals(root) ? null : root.relativize(p.getParent()).toString());
                ObjectNode request = json.createObjectNode().put("expected", baseline.path("revision").asLong()).put("action", "relocate").put("name", p.getFileName().toString());
                if (parent == null) request.putNull("parent");
                else request.put("parent", parent);
                ObjectNode op = operation("folderRelocate", target).put("id", id).put("oldPath", old);
                op.set("request", request);
                journal(op);
                runPending();
            }
        var ids = new ArrayList<String>();
        folders().propertyNames().iterator().forEachRemaining(ids::add);
        ids.sort(Comparator.comparingInt(id -> folders().path(id).path("path").asText().length()));
        for (String id : ids) {
            if (!folders().has(id)) continue;
            JsonNode view = folders().get(id);
            String relative = view.path("path").asText();
            if (rejectedDirectoryDeletes.stream().anyMatch(prefix -> inside(relative, prefix))) continue;
            Path local = safe(relative);
            if (Files.exists(local, LinkOption.NOFOLLOW_LINKS)) {
                if (!Files.isDirectory(local, LinkOption.NOFOLLOW_LINKS))
                    throw new IOException("Tracked directory replaced by non-directory");
                continue;
            }
            ObjectNode op = operation("folderDelete", relative).put("id", id);
            op.set("request", json.createObjectNode().put("expected", view.path("revision").asLong()).put("subtreeHash", view.path("subtreeHash").asText()));
            journal(op);
            runPending();
        }
    }

    List<Path> localPaths() throws Exception {
        var result = new ArrayList<Path>();
        Files.walkFileTree(root, new SimpleFileVisitor<Path>() {
            @Override
            public FileVisitResult preVisitDirectory(Path dir, java.nio.file.attribute.BasicFileAttributes attrs) throws IOException {
                if (!dir.equals(root) && dir.getFileName().toString().startsWith(".drive-"))
                    return FileVisitResult.SKIP_SUBTREE;
                if (!dir.equals(root)) result.add(dir);
                return FileVisitResult.CONTINUE;
            }

            @Override
            public FileVisitResult visitFile(Path file, java.nio.file.attribute.BasicFileAttributes attrs) throws IOException {
                if (Files.isSymbolicLink(file)) throw new IOException("Symlink rejected");
                if (!file.getFileName().toString().startsWith(".drive-")) {
                    if (!attrs.isRegularFile()) throw new IOException("Nonregular file rejected");
                    result.add(file);
                }
                return FileVisitResult.CONTINUE;
            }
        });
        result.sort(Comparator.comparingInt(Path::getNameCount).thenComparing(Path::toString));
        return result;
    }

    void push() throws Exception {
        // Upgrade old pull-state tracking before any remote replay can change its baseline revision.
        files().properties().iterator().forEachRemaining(entry -> {
            ObjectNode f = (ObjectNode) entry.getValue();
            if (!f.has("revision")) f.put("revision", nodes().path(entry.getKey()).path("revision").asLong());
        });
        upgradeFolderViews();
        rejectedDirectoryDeletes.clear();
        runPending();
        List<Path> paths = localPaths();
        pushFolders(paths);
        Map<String, String> directories = new HashMap<>();
        var ids = new ArrayList<String>();
        nodes().propertyNames().iterator().forEachRemaining(ids::add);
        for (String id : ids)
            if (nodes().path(id).path("kind").asText().equals("FOLDER")) {
                String p = path(id, new HashSet<>());
                if (p != null) directories.put(p, id);
            }
        for (Path p : paths)
            if (Files.isDirectory(p, LinkOption.NOFOLLOW_LINKS)) {
                String relative = root.relativize(p).toString();
                directories.put(relative, ensureDirectory(relative));
            }
        Set<String> knownPaths = new HashSet<>();
        files().forEach(f -> knownPaths.add(f.path("path").asText()));
        Map<String, List<Path>> candidates = new HashMap<>();
        Map<String, Integer> missing = new HashMap<>();
        for (Path p : paths)
            if (Files.isRegularFile(p, LinkOption.NOFOLLOW_LINKS) && !knownPaths.contains(root.relativize(p).toString()))
                candidates.computeIfAbsent(hash(p), k -> new ArrayList<>()).add(p);
        for (JsonNode f : files())
            if (!Files.exists(safe(f.path("path").asText()), LinkOption.NOFOLLOW_LINKS))
                missing.merge(f.path("hash").asText(), 1, Integer::sum);
        var trackedIds = new ArrayList<String>();
        files().propertyNames().iterator().forEachRemaining(trackedIds::add);
        for (String id : trackedIds) {
            JsonNode old = files().get(id);
            String relative = old.path("path").asText();
            Path p = safe(relative);
            if (!Files.exists(p, LinkOption.NOFOLLOW_LINKS)) {
                if (rejectedDirectoryDeletes.stream().anyMatch(prefix -> inside(relative, prefix))) continue;
                List<Path> matches = candidates.getOrDefault(old.path("hash").asText(), List.of());
                if (matches.size() == 1 && missing.getOrDefault(old.path("hash").asText(), 0) == 1) {
                    Path destination = matches.getFirst();
                    String target = root.relativize(destination).toString();
                    String parent = destination.getParent().equals(root) ? null : directories.get(root.relativize(destination.getParent()).toString());
                    if (!destination.getParent().equals(root) && parent == null)
                        throw new IOException("Unmapped relocation parent");
                    ObjectNode request = json.createObjectNode().put("expected", old.path("revision").asLong()).put("action", "relocate").put("name", destination.getFileName().toString());
                    if (parent == null) request.putNull("parent");
                    else request.put("parent", parent);
                    ObjectNode op = operation("relocate", target).put("id", id);
                    op.set("request", request);
                    journal(op);
                    runPending();
                } else {
                    ObjectNode op = operation("delete", relative).put("id", id);
                    op.set("request", json.createObjectNode().put("expected", old.path("revision").asLong()).put("action", "delete"));
                    journal(op);
                    runPending();
                }
            } else if (!Files.isRegularFile(p, LinkOption.NOFOLLOW_LINKS))
                throw new IOException("Tracked file replaced by nonregular path");
            else if (!hash(p).equals(old.path("hash").asText())) {
                queueUpload(relative, id, old, null);
                runPending();
            }
        }
        Set<String> tracked = new HashSet<>();
        files().forEach(f -> tracked.add(f.path("path").asText()));
        for (Path p : paths)
            if (Files.isRegularFile(p, LinkOption.NOFOLLOW_LINKS)) {
                String relative = root.relativize(p).toString();
                if (tracked.contains(relative)) continue;
                String parent = p.getParent().equals(root) ? null : directories.get(root.relativize(p.getParent()).toString());
                if (!p.getParent().equals(root) && parent == null)
                    throw new IOException("Unmapped local parent; rename a case-colliding directory");
                queueUpload(relative, null, json.nullNode(), parent);
                runPending();
            }
    }

    Path safe(String relative) throws Exception {
        Path p = root.resolve(relative).normalize();
        if (!p.startsWith(root) || p.equals(root) || p.getFileName().toString().startsWith(".drive-"))
            throw new IOException("Unsafe path");
        for (Path q = p; q != null && q.startsWith(root); q = q.getParent())
            if (Files.isSymbolicLink(q)) throw new IOException("Symlink rejected");
        return p;
    }

    String path(String id, Set<String> visiting) throws Exception {
        if (!visiting.add(id)) throw new IOException("Cycle");
        JsonNode n = state.path("nodes").get(id);
        if (n == null || n.path("deleted").asBoolean()) return null;
        String name = n.path("name").asText();
        if (name.contains("/") || name.contains("\\") || name.equals("..") || name.equals("."))
            throw new IOException("Unsafe name");
        if (n.path("parent").isNull()) return name;
        String parent = path(n.path("parent").asText(), visiting);
        return parent == null ? null : parent + "/" + name;
    }

    String hash(Path p) throws Exception {
        var digest = MessageDigest.getInstance("SHA-256");
        try (var in = Files.newInputStream(p)) {
            byte[] b = new byte[131072];
            int n;
            while ((n = in.read(b)) != -1) digest.update(b, 0, n);
        }
        return Base64.getEncoder().encodeToString(digest.digest());
    }

    void preserve(Path p, String expected) throws Exception {
        if (!Files.exists(p)) return;
        if (!Files.isRegularFile(p)) throw new IOException("Expected regular file");
        if (expected == null || !hash(p).equals(expected))
            Files.move(p, p.resolveSibling(conflictName(p.getFileName().toString(), ".conflict-" + UUID.randomUUID())));
        else Files.delete(p);
    }

    void bootstrap() throws Exception {
        if (subscription != null) {
            // Recipient history starts with a materialized baseline; it never contains pre-grant owner events.
            state.set("nodes", json.createObjectNode());
            state.put("cursor", 0).put("bootstrapped", true);
            return;
        }
        // Retain the FIRST boundary: later pages may observe newer commits.
        ObjectNode nodes = json.createObjectNode();
        String after = "00000000-0000-0000-0000-000000000000";
        long boundary = -1;
        while (true) {
            JsonNode page = get("/sync/snapshot?after=" + after + "&limit=500");
            if (boundary < 0) boundary = page.path("boundary").asLong();
            for (JsonNode n : page.path("nodes")) nodes.set(n.path("id").asText(), n);
            String next = page.path("after").asText();
            if (next.equals(after)) break;
            after = next;
        }
        state.set("nodes", nodes);
        state.put("cursor", boundary);
        state.put("bootstrapped", true);
    }

    void once() throws Exception {
        String namespace = get("/me").path("id").asText();
        if (state.has("namespace") && !namespace.equals(state.path("namespace").asText()))
            throw new IOException("Mirror belongs to another namespace");
        String selected = subscription == null ? "" : subscription;
        if (state.path("bootstrapped").asBoolean() && !selected.equals(state.path("subscription").asText("")))
            throw new IOException("Mirror belongs to another synchronization scope");
        state.put("namespace", namespace).put("subscription", selected);
        if (subscription != null) sharedNamespace = get("/sync/subscriptions/" + subscription).path("ns").asText();
        if (bidirectional && (releaseQuarantine || state.path("pending").path("releasingQuarantine").asBoolean())) {
            releasePendingQuarantine();
            releaseQuarantine = false;
        }
        if (bidirectional) push();
        else if (state.has("pending")) throw new IOException("Pending writes require --bidirectional");
        if (!state.path("bootstrapped").asBoolean()) bootstrap();
        long cursor = 0;
        boolean replayed = false;
        for (int attempt = 0; attempt < 3 && !replayed; attempt++) {
            cursor = state.path("cursor").asLong();
            try {
                // Drain before materializing; notifications and intermediate versions are only hints.
                while (true) {
                    JsonNode page = get("/sync/changes?cursor=" + cursor + "&limit=500");
                    for (JsonNode event : page.path("changes")) {
                        JsonNode n = event.path("payload");
                        ((ObjectNode) state.get("nodes")).set(n.path("id").asText(), n);
                    }
                    long next = page.path("cursor").asLong();
                    if (next == cursor) break;
                    cursor = next;
                }
                replayed = true;
            } catch (CursorExpired expired) {
                // Keep tracked paths/hashes to preserve edits and remove paths absent from a fresh snapshot.
                state.put("bootstrapped", false);
                bootstrap();
            }
        }
        if (!replayed) throw new IOException("Snapshot repeatedly expired; retry later");
        ObjectNode files = (ObjectNode) state.get("files");
        var ids = new ArrayList<String>();
        state.path("nodes").propertyNames().iterator().forEachRemaining(ids::add);
        // Remove old tracked paths first; this makes folder moves and file-name swaps deterministic.
        var trackedIds = new ArrayList<String>();
        files.propertyNames().iterator().forEachRemaining(trackedIds::add);
        for (String id : trackedIds) {
            JsonNode old = files.get(id);
            String target = path(id, new HashSet<>());
            if (old != null && !Objects.equals(target, old.path("path").asText())) {
                preserve(safe(old.path("path").asText()), old.path("hash").asText());
                files.remove(id);
            }
        }
        Set<String> wantedDirectories = new HashSet<>();
        for (String id : ids)
            if (nodes().path(id).path("kind").asText().equals("FOLDER")) {
                String relative = path(id, new HashSet<>());
                if (relative != null) wantedDirectories.add(relative);
            }
        var staleDirs = new ArrayList<String>();
        for (JsonNode directory : state.path("directories")) staleDirs.add(directory.asText());
        staleDirs.sort(Comparator.comparingInt(String::length).reversed());
        for (String directory : staleDirs) {
            if (wantedDirectories.contains(directory)) continue;
            try {
                Path obsolete = safe(directory);
                Files.deleteIfExists(obsolete.resolve(".drive-folder-id"));
                Files.deleteIfExists(obsolete);
            } catch (DirectoryNotEmptyException ignored) {
            }
        }
        ArrayNode directories = json.createArrayNode();
        for (String id : ids) {
            JsonNode n = state.path("nodes").get(id);
            String relative = path(id, new HashSet<>());
            if (relative == null) continue;
            Path p = safe(relative);
            if (n.path("kind").asText().equals("FOLDER")) {
                Files.createDirectories(p);
                directories.add(relative);
                markFolder(relative, id);
                continue;
            }
            JsonNode old = files.get(id);
            String version = n.path("current_version").asText();
            if (old != null && old.path("version").asText().equals(version) && Files.exists(p)) {
                ((ObjectNode) old).put("revision", n.path("revision").asLong());
                continue;
            }
            JsonNode download = get("/files/" + id + "/download?version=" + version);
            Files.createDirectories(p.getParent());
            Path temp = Files.createTempFile(root, ".drive-download-", ".tmp");
            try {
                var response = http.send(HttpRequest.newBuilder(URI.create(download.path("url").asText())).timeout(Duration.ofMinutes(30)).GET().build(), HttpResponse.BodyHandlers.ofFile(temp));
                if (response.statusCode() != 200) throw new IOException("Download status " + response.statusCode());
                String checksum = hash(temp);
                if (!checksum.equals(download.path("sha256").asText()) || Files.size(temp) != download.path("size").asLong())
                    throw new IOException("Integrity mismatch");
                if (Files.exists(p) && hash(p).equals(checksum)) Files.delete(temp);
                else {
                    preserve(p, old == null ? null : old.path("hash").asText());
                    Files.move(temp, p, StandardCopyOption.ATOMIC_MOVE);
                }
                ObjectNode tracked = json.createObjectNode();
                tracked.put("path", relative).put("hash", checksum).put("version", version).put("revision", n.path("revision").asLong());
                files.set(id, tracked);
            } finally {
                Files.deleteIfExists(temp);
            }
        }
        state.set("directories", directories);
        state.set("folderViews", json.createObjectNode());
        for (String id : ids)
            if (nodes().path(id).path("kind").asText().equals("FOLDER")) {
                String relative = path(id, new HashSet<>());
                if (relative != null) recordFolder(id, relative);
            }
        state.put("cursor", cursor);
        save();
    }

    static class CursorExpired extends IOException {
    }

    static class ApiFailure extends IOException {
        final int status;

        ApiFailure(int status) {
            super("API status " + status);
            this.status = status;
        }
    }
}
