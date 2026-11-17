package com.example.drive;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.s3.S3Client;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.*;
import java.util.concurrent.*;

import static org.junit.jupiter.api.Assertions.*;

@org.springframework.test.annotation.DirtiesContext(classMode = org.springframework.test.annotation.DirtiesContext.ClassMode.AFTER_CLASS)
@Tag("integration")
@Testcontainers
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {"drive.local=true", "drive.local-password=integration-password", "drive.reconcile-ms=3600000"})
class IntegrationTest {
    @Container
    static PostgreSQLContainer<?> pg = new PostgreSQLContainer<>("postgres:17-alpine");
    @Container
    static GenericContainer<?> s3 = new GenericContainer<>("localstack/localstack:4.10.0").withEnv("SERVICES", "s3").withExposedPorts(4566).waitingFor(org.testcontainers.containers.wait.strategy.Wait.forHttp("/_localstack/health").forPort(4566));
    @Autowired
    Drive d;
    @Autowired
    SharedSync sharedSync;
    @org.springframework.boot.test.web.server.LocalServerPort
    int port;
    @org.springframework.test.context.bean.override.mockito.MockitoSpyBean
    S3Objects storage;

    static String endpoint() {
        return "http://" + s3.getHost() + ":" + s3.getMappedPort(4566);
    }

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry r) {
        System.setProperty("aws.accessKeyId", "integration");
        System.setProperty("aws.secretAccessKey", "integration-password");
        r.add("spring.datasource.url", pg::getJdbcUrl);
        r.add("spring.datasource.username", pg::getUsername);
        r.add("spring.datasource.password", pg::getPassword);
        r.add("drive.s3.endpoint", IntegrationTest::endpoint);
        r.add("drive.s3.public-endpoint", IntegrationTest::endpoint);
    }

    @BeforeAll
    static void bucket() {
        try (var client = S3Client.builder().endpointOverride(URI.create(endpoint())).region(Region.US_EAST_1).forcePathStyle(true).credentialsProvider(StaticCredentialsProvider.create(AwsBasicCredentials.create("integration", "integration-password"))).build()) {
            client.createBucket(b -> b.bucket("drive"));
        }
    }

    Drive.Actor user() {
        return new Drive.Actor("tenant", UUID.randomUUID().toString());
    }

    UUID folder(Drive.Actor a, UUID ns, UUID parent, String name) {
        return (UUID) d.createFolder(a, ns, parent, name).get("id");
    }

    @Test
    void uploadDownloadResumeVersionAndPermission() throws Exception {
        var a = user();
        UUID ns = d.home(a);
        byte[] bytes = "verified content".getBytes();
        String sha = Rules.hash(bytes);
        var request = new Drive.UploadRequest(null, "report.txt", null, 0, bytes.length, sha);
        var u = d.initiate(a, ns, "key", request);
        UUID id = (UUID) u.get("id");
        assertEquals(id, d.initiate(a, ns, "key", request).get("id"));
        assertThrows(Exception.class, () -> d.initiate(a, ns, "key", new Drive.UploadRequest(null, "other", null, 0, bytes.length, sha)));
        var signed = d.part(a, ns, id, 1, sha);
        var b = HttpRequest.newBuilder(URI.create(signed.get("url").toString())).PUT(HttpRequest.BodyPublishers.ofByteArray(bytes));
        @SuppressWarnings("unchecked") var headers = (Map<String, List<String>>) signed.get("headers");
        headers.forEach((k, v) -> {
            if (!k.equalsIgnoreCase("host")) v.forEach(value -> b.header(k, value));
        });
        var client = HttpClient.newHttpClient();
        assertEquals(200, client.send(b.build(), HttpResponse.BodyHandlers.ofString()).statusCode());
        assertFalse(((List<?>) d.status(a, ns, id).get("parts")).isEmpty());
        var n = d.complete(a, ns, id);
        assertEquals(n.get("id"), d.complete(a, ns, id).get("id"));
        UUID node = (UUID) n.get("id");
        var inspection = d.one("SELECT * FROM processing_jobs WHERE source=?", id);
        Object jobId = inspection.get("id");
        d.db.update("UPDATE processing_jobs SET state='RUNNING',fence=100,lease_until=now()-interval '1 second' WHERE id=?", jobId);
        d.db.update("UPDATE processing_jobs SET fence=101,lease_until=now()+interval '60 seconds' WHERE id=?", jobId);
        assertEquals(0, d.db.update("UPDATE processing_jobs SET state='SUCCEEDED' WHERE id=? AND fence=100 AND state='RUNNING' AND lease_until>now()", jobId));
        d.db.update("UPDATE processing_jobs SET state='PENDING',available_at=now() WHERE id=?", jobId);
        for (int attempt=0; attempt<100 && !d.one("SELECT state FROM processing_jobs WHERE id=?", jobId).get("state").equals("SUCCEEDED"); attempt++) { new Processing(d).tick(); Thread.sleep(50); }
        assertEquals("SUCCEEDED",d.one("SELECT state FROM processing_jobs WHERE id=?",jobId).get("state"));
        var artifact=d.one("SELECT * FROM processed_artifacts WHERE job=? AND variant='document-metadata'",jobId);
        try(var artifactBytes=d.objects.open(artifact.get("object_key").toString())) {
            assertEquals(artifact.get("sha256"),Rules.hash(artifactBytes.readAllBytes()));
        }
        assertEquals(1,d.db.queryForObject("SELECT count(*) FROM garbage_objects WHERE object_key=?",Integer.class,artifact.get("object_key")));

        var artifactUrl=d.objects.download(artifact.get("object_key").toString(),"metadata.json");
        var artifactBytes=client.send(HttpRequest.newBuilder(URI.create(artifactUrl)).build(),HttpResponse.BodyHandlers.ofByteArray()).body();
        assertEquals(artifact.get("sha256"),Rules.hash(artifactBytes));
        assertTrue(new String(artifactBytes).contains("UTF-8"));

        var url = d.download(a, ns, node, null).get("url").toString();
        assertArrayEquals(bytes, client.send(HttpRequest.newBuilder(URI.create(url)).build(), HttpResponse.BodyHandlers.ofByteArray()).body());
        var range = client.send(HttpRequest.newBuilder(URI.create(url)).header("Range", "bytes=1-3").build(), HttpResponse.BodyHandlers.ofByteArray());
        assertEquals(206, range.statusCode());
        assertArrayEquals(Arrays.copyOfRange(bytes, 1, 4), range.body());
        assertEquals("bytes 1-3/" + bytes.length, range.headers().firstValue("Content-Range").orElseThrow());
        assertEquals(bytes.length, ((Number) d.namespace(ns).get("used")).intValue());
        var bob = new Drive.Actor("tenant", "bob");
        assertThrows(Exception.class, () -> d.read(bob, ns, node));
        UUID share = d.share(a, ns, node, "bob", "VIEWER");
        assertNotNull(d.read(bob, ns, node));
        assertThrows(Exception.class, () -> d.mutate(bob, ns, node, 2, "rename", "bad", null));
        d.revoke(a, ns, node, share);
        assertThrows(Exception.class, () -> d.read(bob, ns, node));
        var restored = d.restoreVersion(a, ns, node, id, 2);
        assertEquals(3L, restored.get("revision"));
        assertEquals(2, d.versions(a, ns, node).size());
        UUID copy = (UUID) d.copy(a, ns, node, null, "copy.txt").get("id");
        assertNotNull(d.download(a, ns, copy, null));
        assertEquals(1, d.retain(a, ns, node, 1));
        var link = d.link(a, ns, node, 30);
        assertNotNull(d.publicDownload(link.get("token").toString()));
        d.revoke(a, ns, node, (UUID) link.get("id"));
        assertThrows(Exception.class, () -> d.publicDownload(link.get("token").toString()));
        assertThrows(Exception.class, () -> d.mutate(a, ns, node, 2, "rename", "stale", null));
    }

    @Test
    void foldersCyclesTenantIsolationAndTombstones() {
        var a = user();
        UUID ns = d.home(a), root = folder(a, ns, null, "root"), child = folder(a, ns, root, "child");
        assertThrows(Exception.class, () -> d.mutate(a, ns, root, 1, "move", null, child));
        assertThrows(Exception.class, () -> d.read(new Drive.Actor("wrong", a.subject()), ns, child));
        d.mutate(a, ns, root, 1, "delete", null, null);
        assertThrows(Exception.class, () -> d.read(a, ns, child));
        d.mutate(a, ns, root, 2, "restore", null, null);
        assertNotNull(d.read(a, ns, child));
    }

    @Test
    void quotaRaceAndCursorCommitOrdering() throws Exception {
        var a = user();
        UUID ns = d.home(a);
        d.db.update("UPDATE namespaces SET quota=10 WHERE id=?", ns);
        try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
            var tasks = new ArrayList<Future<Boolean>>();
            for (int i = 0; i < 8; i++) {
                int n = i;
                tasks.add(executor.submit(() -> {
                    try {
                        d.initiate(a, ns, "k" + n, new Drive.UploadRequest(null, "f" + n, null, 0, 6, Rules.hash("abcdef")));
                        return true;
                    } catch (Exception e) {
                        return false;
                    }
                }));
            }
            int ok = 0;
            for (var f : tasks) if (f.get()) ok++;
            assertEquals(1, ok);
            CountDownLatch allocated = new CountDownLatch(1), release = new CountDownLatch(1);
            var first = executor.submit(() -> d.locked(a, ns, () -> {
                folder(a, ns, null, "first");
                allocated.countDown();
                try {
                    release.await();
                } catch (InterruptedException e) {
                    throw new RuntimeException(e);
                }
                return null;
            }));
            assertTrue(allocated.await(5, TimeUnit.SECONDS));
            var second = executor.submit(() -> folder(a, ns, null, "second"));
            assertTrue(((List<?>) d.changes(a, ns, "0", 100).get("changes")).isEmpty());
            release.countDown();
            first.get(10, TimeUnit.SECONDS);
            second.get(10, TimeUnit.SECONDS);
            var page = d.changes(a, ns, "0", 1);
            assertEquals(1L, page.get("cursor"));
            assertEquals(2L, d.changes(a, ns, "1", 100).get("cursor"));
        }
    }

    @Test
    void ambiguousCompletionRecoversAndClientPersistsCursor() throws Exception {
        var a = new Drive.Actor("local", "alice");
        UUID ns = d.home(a);
        byte[] bytes = "client mirrored bytes".getBytes();
        String sha = Rules.hash(bytes);
        var upload = d.initiate(a, ns, UUID.randomUUID().toString(), new Drive.UploadRequest(null, "mirror.txt", null, 0, bytes.length, sha));
        UUID id = (UUID) upload.get("id");
        var signed = d.part(a, ns, id, 1, sha);
        var b = HttpRequest.newBuilder(URI.create(signed.get("url").toString())).PUT(HttpRequest.BodyPublishers.ofByteArray(bytes));
        @SuppressWarnings("unchecked") var headers = (Map<String, List<String>>) signed.get("headers");
        headers.forEach((k, v) -> {
            if (!k.equalsIgnoreCase("host")) v.forEach(value -> b.header(k, value));
        });
        assertEquals(200, HttpClient.newHttpClient().send(b.build(), HttpResponse.BodyHandlers.ofString()).statusCode());
        org.mockito.Mockito.doThrow(new IllegalStateException("injected post-complete failure")).doCallRealMethod().when(storage).verify(org.mockito.ArgumentMatchers.anyString(), org.mockito.ArgumentMatchers.anyLong(), org.mockito.ArgumentMatchers.anyString());
        assertThrows(IllegalStateException.class, () -> d.complete(a, ns, id));
        assertEquals("COMPLETING", d.status(a, ns, id).get("state"));
        assertEquals(0L, d.namespace(ns).get("used"));
        var node = d.complete(a, ns, id);
        assertEquals(bytes.length, ((Number) d.namespace(ns).get("used")).intValue());
        java.nio.file.Path mirror = java.nio.file.Files.createTempDirectory("drive-mirror-");
        // Run the real source-file Java client with the test JVM's resolved dependencies.
        String cp = System.getProperty("surefire.test.class.path", System.getProperty("java.class.path"));
        for (int run = 0; run < 4; run++) {
            if (run == 2) {
                java.nio.file.Files.writeString(mirror.resolve("mirror.txt"), "offline edits");
                d.mutate(a, ns, (UUID) node.get("id"), 2, "rename", "renamed.txt", null);
            }
            if (run == 3) d.mutate(a, ns, (UUID) node.get("id"), 3, "delete", null, null);
            var process = new ProcessBuilder(System.getProperty("java.home") + "/bin/java", "--class-path", cp, "sync-client/SyncClient.java", "http://localhost:" + port + "/api/v1", mirror.toString(), "--once");
            process.environment().put("DRIVE_AUTH", "Basic " + Base64.getEncoder().encodeToString("alice:integration-password".getBytes()));
            var result = process.redirectErrorStream(true).start();
            String output = new String(result.getInputStream().readAllBytes());
            assertTrue(result.waitFor(30, TimeUnit.SECONDS));
            assertEquals(0, result.exitValue(), output);
            if (run < 2) assertArrayEquals(bytes, java.nio.file.Files.readAllBytes(mirror.resolve("mirror.txt")));
            if (run == 2) {
                assertArrayEquals(bytes, java.nio.file.Files.readAllBytes(mirror.resolve("renamed.txt")));
                try (var paths = java.nio.file.Files.list(mirror)) {
                    assertTrue(paths.anyMatch(p -> p.getFileName().toString().startsWith("mirror.txt.conflict-")));
                }
            }
            if (run == 3) assertFalse(java.nio.file.Files.exists(mirror.resolve("renamed.txt")));
            assertTrue(java.nio.file.Files.exists(mirror.resolve(".drive-state.json")));
        }
    }

    @Test
    void twoPartsMissingPartRetryAndParallelCompletion() throws Exception {
        var a = user();
        UUID ns = d.home(a);
        byte[] bytes = new byte[8 * 1024 * 1024 + 123];
        new Random(7).nextBytes(bytes);
        String sha = Rules.hash(bytes);
        var u = d.initiate(a, ns, "multipart", new Drive.UploadRequest(null, "large.bin", null, 0, bytes.length, sha));
        UUID id = (UUID) u.get("id");
        for (int number = 1; number <= 2; number++) {
            if (number == 2) {
                assertThrows(Exception.class, () -> d.complete(a, ns, id));
                assertEquals("UPLOADING", d.status(a, ns, id).get("state"));
            }
            byte[] chunk = Arrays.copyOfRange(bytes, (number - 1) * 8 * 1024 * 1024, number == 1 ? 8 * 1024 * 1024 : bytes.length);
            var signed = d.part(a, ns, id, number, Rules.hash(chunk));
            var b = HttpRequest.newBuilder(URI.create(signed.get("url").toString())).PUT(HttpRequest.BodyPublishers.ofByteArray(chunk));
            @SuppressWarnings("unchecked") var headers = (Map<String, List<String>>) signed.get("headers");
            headers.forEach((k, v) -> {
                if (!k.equalsIgnoreCase("host")) v.forEach(value -> b.header(k, value));
            });
            assertEquals(200, HttpClient.newHttpClient().send(b.build(), HttpResponse.BodyHandlers.ofString()).statusCode());
        }
        try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
            var first = executor.submit(() -> d.complete(a, ns, id));
            var second = executor.submit(() -> d.complete(a, ns, id));
            assertEquals(first.get().get("current_version"), second.get().get("current_version"));
        }
        assertEquals((long) bytes.length, d.namespace(ns).get("used"));
        assertEquals(1, d.db.queryForObject("SELECT count(*) FROM file_versions WHERE id=?", Integer.class, id));
    }

    @Test
    void httpSecurityRejectsUnauthenticatedAndUnauthorizedUsers() throws Exception {
        var client = HttpClient.newHttpClient();
        String base = "http://localhost:" + port + "/api/v1";
        assertEquals(401, client.send(HttpRequest.newBuilder(URI.create(base + "/files")).GET().build(), HttpResponse.BodyHandlers.ofString()).statusCode());
        var alice = new Drive.Actor("local", "alice");
        UUID ns = d.home(alice), id = folder(alice, ns, null, "private-" + UUID.randomUUID());
        String bob = "Basic " + Base64.getEncoder().encodeToString("bob:integration-password".getBytes());
        assertEquals(403, client.send(HttpRequest.newBuilder(URI.create(base + "/files/" + id + "?ns=" + ns)).header("Authorization", bob).GET().build(), HttpResponse.BodyHandlers.ofString()).statusCode());
    }

    void assertStatus(int code, org.junit.jupiter.api.function.Executable work) {
        assertEquals(code, assertThrows(org.springframework.web.server.ResponseStatusException.class, work).getStatusCode().value());
    }

    UUID put(Drive.Actor a, UUID ns, UUID parent, String name, byte[] bytes, String finalHash) throws Exception {
        var u = d.initiate(a, ns, UUID.randomUUID().toString(), new Drive.UploadRequest(parent, name, null, 0, bytes.length, finalHash));
        UUID id = (UUID) u.get("id");
        sendPart(a, ns, id, bytes);
        return id;
    }

    void sendPart(Drive.Actor a, UUID ns, UUID id, byte[] bytes) throws Exception {
        var signed = d.part(a, ns, id, 1, Rules.hash(bytes));
        var request = HttpRequest.newBuilder(URI.create(signed.get("url").toString())).PUT(HttpRequest.BodyPublishers.ofByteArray(bytes));
        @SuppressWarnings("unchecked") var headers = (Map<String, List<String>>) signed.get("headers");
        headers.forEach((k, v) -> {
            if (!k.equalsIgnoreCase("host")) v.forEach(value -> request.header(k, value));
        });
        assertEquals(200, HttpClient.newHttpClient().send(request.build(), HttpResponse.BodyHandlers.ofString()).statusCode());
    }

    @Test
    void emptyFileIntegrityAndHistoryPagination() throws Exception {
        var a = user();
        UUID ns = d.home(a);
        byte[] bytes = new byte[0];
        UUID upload = put(a, ns, null, "empty.txt", bytes, Rules.hash(bytes));
        var n = d.complete(a, ns, upload);
        UUID id = (UUID) n.get("id");
        var url = d.download(a, ns, id, null).get("url").toString();
        var response = HttpClient.newHttpClient().send(HttpRequest.newBuilder(URI.create(url)).build(), HttpResponse.BodyHandlers.ofByteArray());
        assertEquals(200, response.statusCode());
        assertEquals(0, response.body().length);
        d.restoreVersion(a, ns, id, upload, 2);
        d.restoreVersion(a, ns, id, upload, 3);
        var first = d.versions(a, ns, id, null, 1);
        var second = d.versions(a, ns, id, (UUID) first.getFirst().get("id"), 2);
        assertEquals(2, second.size());
        assertNotEquals(first.getFirst().get("id"), second.getFirst().get("id"));
        assertEquals(0L, d.namespace(ns).get("used"));
        assertThrows(org.springframework.dao.DataAccessException.class, () -> d.db.update("UPDATE file_versions SET size=1 WHERE id=?", upload));
        UUID bad = put(a, ns, null, "bad.txt", "bytes".getBytes(), Rules.hash("wrong"));
        assertStatus(422, () -> d.complete(a, ns, bad));
        assertEquals("COMPLETING", d.status(a, ns, bad).get("state"));
        d.abort(a, ns, bad);
        assertEquals(0L, d.namespace(ns).get("reserved"));
    }

    @Test
    void revokedUploaderCanCancelButCannotPublishOrGetNewUrls() throws Exception {
        var a = user();
        var editor = new Drive.Actor(a.tenant(), "editor");
        UUID ns = d.home(a), parent = folder(a, ns, null, "shared");
        UUID grant = d.share(a, ns, parent, editor.subject(), "EDITOR");
        byte[] bytes = "revoked".getBytes();
        UUID upload = put(editor, ns, parent, "never-published", bytes, Rules.hash(bytes));
        d.revoke(a, ns, parent, grant);
        assertStatus(403, () -> d.part(editor, ns, upload, 1, Rules.hash(bytes)));
        assertStatus(403, () -> d.complete(editor, ns, upload));
        d.abort(editor, ns, upload);
        d.abort(editor, ns, upload);
        assertEquals(0L, d.namespace(ns).get("reserved"));
        assertEquals(0L, d.namespace(ns).get("used"));
        assertEquals("ABORTED", d.status(editor, ns, upload).get("state"));
    }

    @Test
    void cursorValidationIdentityIsolationAndSnapshotReplay() {
        var a = user();
        UUID ns = d.home(a);
        assertStatus(400, () -> d.changes(a, ns, "100", 100));
        UUID root = folder(a, ns, null, "root");
        var first = d.snapshot(a, ns, UUID.fromString(Api.ZERO), 1);
        long boundary = ((Number) first.get("boundary")).longValue();
        folder(a, ns, root, "late");
        assertEquals(boundary + 1, d.changes(a, ns, Long.toString(boundary), 100).get("cursor"));
        assertStatus(400, () -> d.mutate(a, ns, root, 1, null, null, null));
        assertNotEquals(d.home(new Drive.Actor("a:b", "c")), d.home(new Drive.Actor("a", "b:c")));
        d.db.update("UPDATE namespaces SET active=false WHERE id=?", ns);
        assertStatus(403, () -> d.profile(a));
    }

    @Test
    void filteredSearchPrunesDeletedSubtreesAndHonorsScope() throws Exception {
        var a = user();
        UUID ns = d.home(a), root = folder(a, ns, null, "root"), child = folder(a, ns, root, "nested");
        UUID upload = put(a, ns, child, "Report.TXT", "text".getBytes(), Rules.hash("text"));
        d.complete(a, ns, upload);
        var rows = d.search(a, ns, root, "report", "FILE", "txt", a.subject(), java.time.Instant.now().minusSeconds(60), null, UUID.fromString(Api.ZERO), 1);
        assertEquals(1, rows.size());
        assertTrue(d.search(a, ns, root, "", "FILE", null, "other", null, null, UUID.fromString(Api.ZERO), 100).isEmpty());
        assertTrue(d.search(a, ns, root, "", "FILE", null, null, null, null, (UUID) rows.getFirst().get("id"), 100).isEmpty());
        var viewer = new Drive.Actor(a.tenant(), "viewer");
        d.share(a, ns, root, viewer.subject(), "VIEWER");
        assertEquals(1, d.search(viewer, ns, root, "", "FILE", null, null, null, null, UUID.fromString(Api.ZERO), 100).size());
        assertStatus(403, () -> d.search(viewer, ns, null, "", null, null, null, null, null, UUID.fromString(Api.ZERO), 100));
        d.mutate(a, ns, child, 1, "delete", null, null);
        assertTrue(d.search(a, ns, null, "", "FILE", null, null, null, null, UUID.fromString(Api.ZERO), 100).isEmpty());
    }

    @Test
    void racingMovesCannotCreateCycle() throws Exception {
        var a = user();
        UUID ns = d.home(a), x = folder(a, ns, null, "x"), y = folder(a, ns, null, "y");
        var gate = new CountDownLatch(1);
        try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
            var first = executor.submit(() -> {
                gate.await();
                try {
                    d.mutate(a, ns, x, 1, "move", null, y);
                    return true;
                } catch (org.springframework.web.server.ResponseStatusException e) {
                    assertEquals(409, e.getStatusCode().value());
                    return false;
                }
            });
            var second = executor.submit(() -> {
                gate.await();
                try {
                    d.mutate(a, ns, y, 1, "move", null, x);
                    return true;
                } catch (org.springframework.web.server.ResponseStatusException e) {
                    assertEquals(409, e.getStatusCode().value());
                    return false;
                }
            });
            gate.countDown();
            assertNotEquals(first.get(10, TimeUnit.SECONDS), second.get(10, TimeUnit.SECONDS));
        }
    }

    @Test
    void fileOnlyEditorCanPublishAndReconcilerReleasesIrrecoverableReservation() throws Exception {
        var a = user();
        UUID ns = d.home(a);
        byte[] bytes = "initial".getBytes();
        UUID original = put(a, ns, null, "direct.txt", bytes, Rules.hash(bytes));
        UUID file = (UUID) d.complete(a, ns, original).get("id");
        var editor = new Drive.Actor(a.tenant(), "file-editor");
        d.share(a, ns, file, editor.subject(), "EDITOR");
        var u = d.initiate(editor, ns, "direct-edit", new Drive.UploadRequest(null, "direct.txt", file, 2, bytes.length, Rules.hash(bytes)));
        UUID id = (UUID) u.get("id");
        sendPart(editor, ns, id, bytes);
        assertEquals(3L, d.complete(editor, ns, id).get("revision"));
        UUID bad = put(a, ns, null, "corrupt", bytes, Rules.hash("wrong"));
        assertStatus(422, () -> d.complete(a, ns, bad));
        var metrics = new io.micrometer.core.instrument.simple.SimpleMeterRegistry();
        new Reconciler(d, metrics).reconcile();
        assertEquals("ABORTED", d.status(a, ns, bad).get("state"));
        assertEquals(0L, d.namespace(ns).get("reserved"));
        var expired = d.initiate(a, ns, "expired", new Drive.UploadRequest(null, "expired", null, 0, bytes.length, Rules.hash(bytes)));
        UUID exp = (UUID) expired.get("id");
        d.db.update("UPDATE upload_sessions SET expires=now()-interval '1 second' WHERE id=?", exp);
        new Reconciler(d, metrics).reconcile();
        assertEquals("ABORTED", d.status(a, ns, exp).get("state"));
    }

    @Test
    void deactivationAbortsReservationsAndDeniesExistingAccess() throws Exception {
        var a = user();
        UUID ns = d.home(a), root = folder(a, ns, null, "deactivate");
        var editor = new Drive.Actor(a.tenant(), "editor");
        d.share(a, ns, root, editor.subject(), "EDITOR");
        UUID pending = put(editor, ns, root, "pending", "data".getBytes(), Rules.hash("data"));
        d.deactivate(a);
        d.deactivate(a);
        assertEquals(0L, d.namespace(ns).get("reserved"));
        assertEquals("ABORTED", d.one("SELECT state FROM upload_sessions WHERE id=?", pending).get("state"));
        assertStatus(403, () -> d.profile(a));
        assertStatus(404, () -> d.read(editor, ns, root));
    }

    @Test
    void pythonTransferHarnessSupportsEmptyAndResumedFiles() throws Exception {
        var dir = java.nio.file.Files.createTempDirectory("drive-transfer-");
        String auth = "Basic " + Base64.getEncoder().encodeToString("bob:integration-password".getBytes());
        for (byte[] bytes : List.of(new byte[0], "harness bytes".getBytes())) {
            var file = dir.resolve(UUID.randomUUID() + ".bin");
            java.nio.file.Files.write(file, bytes);
            for (int run = 0; run < 2; run++) {
                var builder = new ProcessBuilder("python3", "docker/transfer.py", file.toString(), file + ".session.json");
                builder.environment().put("DRIVE_API", "http://localhost:" + port + "/api/v1");
                builder.environment().put("DRIVE_AUTH", auth);
                var process = builder.redirectErrorStream(true).start();
                String output = new String(process.getInputStream().readAllBytes());
                assertTrue(process.waitFor(30, TimeUnit.SECONDS));
                assertEquals(0, process.exitValue(), output);
            }
            var benchmark = new ProcessBuilder("python3", "benchmarks/transfers.py", file.toString());
            benchmark.environment().put("DRIVE_API", "http://localhost:" + port + "/api/v1");
            benchmark.environment().put("DRIVE_AUTH", auth);
            var process = benchmark.redirectErrorStream(true).start();
            String output = new String(process.getInputStream().readAllBytes());
            assertTrue(process.waitFor(30, TimeUnit.SECONDS));
            assertEquals(0, process.exitValue(), output);
            var report = new tools.jackson.databind.ObjectMapper().readTree(output);
            assertEquals(1, report.path("successes").asInt());
            assertEquals(0, report.path("errors").asInt());
        }
    }

    @Test
    void transientCleanupFailureBacksOffWithoutReleasingQuota() {
        var a = user();
        UUID ns = d.home(a);
        var u = d.initiate(a, ns, "retry", new Drive.UploadRequest(null, "retry", null, 0, 4, Rules.hash("data")));
        UUID id = (UUID) u.get("id");
        String key = d.one("SELECT object_key FROM upload_sessions WHERE id=?", id).get("object_key").toString();
        d.db.update("UPDATE upload_sessions SET expires=now()-interval '1 second' WHERE id=?", id);
        org.mockito.Mockito.doThrow(software.amazon.awssdk.core.exception.SdkClientException.create("injected timeout")).doCallRealMethod().when(storage).abort(org.mockito.ArgumentMatchers.eq(key), org.mockito.ArgumentMatchers.isNull());
        var worker = new Reconciler(d, new io.micrometer.core.instrument.simple.SimpleMeterRegistry());
        worker.reconcile();
        var failed = d.one("SELECT failures,retry_at>now() AS delayed FROM upload_sessions WHERE id=?", id);
        assertEquals(1, failed.get("failures"));
        assertEquals(true, failed.get("delayed"));
        assertEquals(4L, d.namespace(ns).get("reserved"));
        worker.reconcile();
        assertEquals("INITIATED", d.status(a, ns, id).get("state"));
        d.db.update("UPDATE upload_sessions SET retry_at=now()-interval '1 second' WHERE id=?", id);
        worker.reconcile();
        assertEquals("ABORTED", d.status(a, ns, id).get("state"));
        assertEquals(0L, d.namespace(ns).get("reserved"));
    }

    @Test
    void upgradeFromV1PreservesExistingNamespace() throws Exception {
        String schema = "upgrade_" + UUID.randomUUID().toString().replace("-", "");
        UUID ns = UUID.randomUUID();
        org.flywaydb.core.Flyway.configure().dataSource(pg.getJdbcUrl(), pg.getUsername(), pg.getPassword()).schemas(schema).defaultSchema(schema).target("1").load().migrate();
        try (var connection = java.sql.DriverManager.getConnection(pg.getJdbcUrl(), pg.getUsername(), pg.getPassword())) {
            connection.setSchema(schema);
            try (var statement = connection.prepareStatement("INSERT INTO namespaces(id,tenant,subject,seq) VALUES(?,'upgrade','existing',42)")) {
                statement.setObject(1, ns);
                statement.executeUpdate();
            }
            org.flywaydb.core.Flyway.configure().dataSource(pg.getJdbcUrl(), pg.getUsername(), pg.getPassword()).schemas(schema).defaultSchema(schema).load().migrate();
            try (var statement = connection.prepareStatement("SELECT seq FROM namespaces WHERE id=?")) {
                statement.setObject(1, ns);
                try (var row = statement.executeQuery()) {
                    assertTrue(row.next());
                    assertEquals(42L, row.getLong(1));
                }
            }
        }
    }

    @Test
    void compactionPublishesFloorAtomicallyAndRetainsContiguousPrefix() {
        var a = user();
        UUID ns = d.home(a);
        for (int i = 0; i < 4; i++) folder(a, ns, null, "entry" + i);
        var retention = new SyncRetention(d, new io.micrometer.core.instrument.simple.SimpleMeterRegistry(), 0);
        // A newer timestamp in the middle must stop compaction, even if later commits look older.
        d.db.update("UPDATE change_log SET created=now()-interval '2 days' WHERE ns=?", ns);
        d.db.update("UPDATE change_log SET created=now() WHERE ns=? AND seq=2", ns);
        var cutoff = java.time.Instant.now().minusSeconds(86400);
        assertEquals(1, retention.compact(ns, cutoff, 100));
        assertStatus(410, () -> d.changes(a, ns, "0", 100));
        var replay = d.changes(a, ns, "1", 100);
        assertEquals(1L, replay.get("minimumCursor"));
        assertEquals(3, ((List<?>) replay.get("changes")).size());
        assertEquals(0, retention.compact(ns, cutoff, 100));
        d.db.update("UPDATE change_log SET created=now()-interval '2 days' WHERE ns=? AND seq=2", ns);
        assertEquals(2, retention.compact(ns, cutoff, 2));
        assertEquals(3L, d.namespace(ns).get("sync_floor"));
        assertEquals(1, retention.compact(ns, cutoff, 2));
        assertEquals(0, retention.compact(ns, cutoff, 2));
        assertTrue(((List<?>) d.changes(a, ns, "4", 100).get("changes")).isEmpty());
        folder(a, ns, null, "after-compaction");
        assertEquals(5L, d.changes(a, ns, "4", 100).get("cursor"));
        assertStatus(403, () -> d.changes(new Drive.Actor(a.tenant(), "stranger"), ns, "0", 100));
    }

    @Test
    void compactionCannotDeleteAnUncommittedEvent() throws Exception {
        var a = user();
        UUID ns = d.home(a);
        var entered = new CountDownLatch(1);
        var release = new CountDownLatch(1);
        var retention = new SyncRetention(d, new io.micrometer.core.instrument.simple.SimpleMeterRegistry(), 0);
        try (var pool = Executors.newVirtualThreadPerTaskExecutor()) {
            var writer = pool.submit(() -> d.locked(a, ns, () -> {
                folder(a, ns, null, "committing");
                entered.countDown();
                try {
                    assertTrue(release.await(10, TimeUnit.SECONDS));
                } catch (InterruptedException e) {
                    throw new RuntimeException(e);
                }
                return null;
            }));
            assertTrue(entered.await(5, TimeUnit.SECONDS));
            var compacted = pool.submit(() -> retention.compact(ns, java.time.Instant.now().plusSeconds(60), 100));
            try {
                assertThrows(TimeoutException.class, () -> compacted.get(200, TimeUnit.MILLISECONDS));
            } finally {
                release.countDown();
            }
            writer.get(10, TimeUnit.SECONDS);
            assertEquals(1, compacted.get(10, TimeUnit.SECONDS));
            assertStatus(410, () -> d.changes(a, ns, "0", 100));
            assertEquals(1L, d.snapshot(a, ns, UUID.fromString(Api.ZERO), 100).get("boundary"));
        }
    }

    @Test
    void actualClientRecoversExpiredCursorAndPreservesOfflineEdits() throws Exception {
        var a = new Drive.Actor("local", "bob");
        UUID ns = d.home(a);
        String name = "expiry-" + UUID.randomUUID() + ".txt";
        byte[] bytes = "remote bytes".getBytes();
        UUID upload = put(a, ns, null, name, bytes, Rules.hash(bytes));
        var node = d.complete(a, ns, upload);
        var mirror = java.nio.file.Files.createTempDirectory("drive-expiry-");
        String cp = System.getProperty("surefire.test.class.path", System.getProperty("java.class.path"));
        for (int run = 0; run < 2; run++) {
            if (run == 1) {
                java.nio.file.Files.writeString(mirror.resolve(name), "offline edits");
                d.mutate(a, ns, (UUID) node.get("id"), 2, "delete", null, null);
                new SyncRetention(d, new io.micrometer.core.instrument.simple.SimpleMeterRegistry(), 0).compact(ns, java.time.Instant.now().plusSeconds(60), 10000);
                assertStatus(410, () -> d.changes(a, ns, "0", 100));
            }
            var builder = new ProcessBuilder(System.getProperty("java.home") + "/bin/java", "--class-path", cp, "sync-client/SyncClient.java", "http://localhost:" + port + "/api/v1", mirror.toString(), "--once");
            builder.environment().put("DRIVE_AUTH", "Basic " + Base64.getEncoder().encodeToString("bob:integration-password".getBytes()));
            var process = builder.redirectErrorStream(true).start();
            String output = new String(process.getInputStream().readAllBytes());
            assertTrue(process.waitFor(30, TimeUnit.SECONDS));
            assertEquals(0, process.exitValue(), output);
        }
        assertFalse(java.nio.file.Files.exists(mirror.resolve(name)));
        try (var paths = java.nio.file.Files.list(mirror)) {
            var conflict = paths.filter(p -> p.getFileName().toString().startsWith(name + ".conflict-")).findFirst().orElseThrow();
            assertEquals("offline edits", java.nio.file.Files.readString(conflict));
        }
        var state = new tools.jackson.databind.ObjectMapper().readTree(mirror.resolve(".drive-state.json").toFile());
        assertEquals(d.namespace(ns).get("seq"), state.path("cursor").asLong());
    }

    @Test
    void concurrentCompactionNeverReturnsAPartialPageAsCompleteHistory() throws Exception {
        var a = user();
        UUID ns = d.home(a);
        for (int i = 0; i < 30; i++) folder(a, ns, null, "concurrent" + i);
        var gate = new CountDownLatch(1);
        var retention = new SyncRetention(d, new io.micrometer.core.instrument.simple.SimpleMeterRegistry(), 0);
        try (var pool = Executors.newVirtualThreadPerTaskExecutor()) {
            var reader = pool.submit(() -> {
                gate.await();
                for (int i = 0; i < 50; i++) {
                    try {
                        var page = d.changes(a, ns, "0", 100);
                        assertEquals(0L, page.get("minimumCursor"));
                        assertEquals(30L, page.get("cursor"));
                        assertEquals(30, ((List<?>) page.get("changes")).size());
                    } catch (org.springframework.web.server.ResponseStatusException expired) {
                        assertEquals(410, expired.getStatusCode().value());
                    }
                }
                return null;
            });
            var compactor = pool.submit(() -> {
                gate.await();
                for (int i = 0; i < 30; i++) retention.compact(ns, java.time.Instant.now().plusSeconds(60), 1);
                return null;
            });
            gate.countDown();
            reader.get(20, TimeUnit.SECONDS);
            compactor.get(20, TimeUnit.SECONDS);
        }
        assertEquals(30L, d.namespace(ns).get("sync_floor"));
    }

    java.nio.file.Path mirror() throws Exception {
        return java.nio.file.Files.createTempDirectory("drive-bidirectional-");
    }

    void sync(java.nio.file.Path directory, boolean bidirectional, boolean success) throws Exception {
        String cp = System.getProperty("surefire.test.class.path", System.getProperty("java.class.path"));
        var args = new ArrayList<>(List.of(System.getProperty("java.home") + "/bin/java", "--class-path", cp, "sync-client/SyncClient.java", "http://localhost:" + port + "/api/v1", directory.toString(), "--once"));
        if (bidirectional) args.add("--bidirectional");
        var builder = new ProcessBuilder(args);
        builder.environment().put("DRIVE_AUTH", "Basic " + Base64.getEncoder().encodeToString("bob:integration-password".getBytes()));
        var output = java.nio.file.Files.createTempFile("drive-client-test-", ".log");
        var process = builder.redirectErrorStream(true).redirectOutput(output.toFile()).start();
        if (!process.waitFor(60, TimeUnit.SECONDS)) {
            process.destroyForcibly();
            fail("Sync client timeout: " + java.nio.file.Files.readString(output));
        }
        String log = java.nio.file.Files.readString(output);
        java.nio.file.Files.deleteIfExists(output);
        if (success) assertEquals(0, process.exitValue(), log);
        else assertNotEquals(0, process.exitValue(), log);
    }

    tools.jackson.databind.JsonNode clientState(java.nio.file.Path directory) throws Exception {
        return new tools.jackson.databind.ObjectMapper().readTree(directory.resolve(".drive-state.json").toFile());
    }

    @Test
    void bidirectionalOfflineCreateEditDeleteAndTwoDeviceConflict() throws Exception {
        var first = mirror();
        var second = mirror();
        String directory = "offline-" + UUID.randomUUID();
        java.nio.file.Files.createDirectories(first.resolve(directory).resolve("nested"));
        var local = first.resolve(directory).resolve("nested/report.txt");
        java.nio.file.Files.writeString(local, "initial local content");
        java.nio.file.Files.write(first.resolve(directory).resolve("empty.bin"), new byte[0]);
        sync(first, true, true);
        sync(second, true, true);
        assertEquals("initial local content", java.nio.file.Files.readString(second.resolve(directory).resolve("nested/report.txt")));
        assertEquals(0, java.nio.file.Files.size(second.resolve(directory).resolve("empty.bin")));
        String id = null;
        var initial = clientState(first);
        var entries = initial.path("files").properties().iterator();
        while (entries.hasNext()) {
            var entry = entries.next();
            if (entry.getValue().path("path").asText().equals(directory + "/nested/report.txt")) id = entry.getKey();
        }
        assertNotNull(id);
        UUID file = UUID.fromString(id);
        var actor = new Drive.Actor("local", "bob");
        UUID ns = d.home(actor);
        // Both devices edit their last synced revision while disconnected from each other.
        java.nio.file.Files.writeString(local, "device A offline edit");
        java.nio.file.Files.writeString(second.resolve(directory).resolve("nested/report.txt"), "device B offline edit");
        sync(first, true, true);
        sync(second, true, true);
        sync(first, true, true);
        assertEquals("device A offline edit", java.nio.file.Files.readString(second.resolve(directory).resolve("nested/report.txt")));
        var conflicts = d.db.queryForList("SELECT id,name FROM file_nodes WHERE ns=? AND parent IS NULL AND name LIKE 'report.txt.conflict-%'", ns);
        assertEquals(1, conflicts.size());
        String conflict = conflicts.getFirst().get("name").toString();
        assertEquals("device B offline edit", java.nio.file.Files.readString(first.resolve(conflict)));
        assertEquals("device B offline edit", java.nio.file.Files.readString(second.resolve(conflict)));
        assertEquals(2, d.versions(actor, ns, file).size());
        // A plain offline delete publishes a tombstone and propagates on reconnect.
        java.nio.file.Files.delete(first.resolve(directory).resolve("empty.bin"));
        sync(first, true, true);
        sync(second, true, true);
        assertFalse(java.nio.file.Files.exists(second.resolve(directory).resolve("empty.bin")));
        // A stale offline delete must not erase another device's newer content.
        java.nio.file.Files.delete(local);
        java.nio.file.Files.writeString(second.resolve(directory).resolve("nested/report.txt"), "newer remote content");
        sync(second, true, true);
        sync(first, true, true);
        assertEquals("newer remote content", java.nio.file.Files.readString(local));
        assertEquals(3, d.versions(actor, ns, file).size());
        // Unique unchanged content identifies an offline file rename+move without changing its ID/history.
        var moved = first.resolve(directory).resolve("archive/renamed.txt");
        java.nio.file.Files.createDirectories(moved.getParent());
        java.nio.file.Files.move(local, moved);
        sync(first, true, true);
        sync(second, true, true);
        assertFalse(java.nio.file.Files.exists(second.resolve(directory).resolve("nested/report.txt")));
        assertEquals("newer remote content", java.nio.file.Files.readString(second.resolve(directory).resolve("archive/renamed.txt")));
        assertEquals("renamed.txt", d.read(actor, ns, file).get("name"));
        assertEquals(3, d.versions(actor, ns, file).size());

    }

    @Test
    void bidirectionalRetryResumesStagedMultipartAfterRestart() throws Exception {
        var directory = mirror();
        String name = "resumable-" + UUID.randomUUID() + ".bin";
        var local = directory.resolve(name);
        byte[] content = new byte[8 * 1024 * 1024 + 123];
        new Random(77).nextBytes(content);
        java.nio.file.Files.write(local, content);
        // The first part reaches S3, then the next URL request fails. The process exits with a durable journal.
        var calls = new java.util.concurrent.atomic.AtomicInteger();
        org.mockito.Mockito.doAnswer(invocation -> {
            if (calls.incrementAndGet() == 2)
                throw software.amazon.awssdk.core.exception.SdkClientException.create("injected second-part outage");
            return invocation.callRealMethod();
        }).when(storage).part(org.mockito.ArgumentMatchers.anyString(), org.mockito.ArgumentMatchers.anyString(), org.mockito.ArgumentMatchers.anyInt(), org.mockito.ArgumentMatchers.anyString());
        try {
            sync(directory, true, false);
        } finally {
            org.mockito.Mockito.doCallRealMethod().when(storage).part(org.mockito.ArgumentMatchers.anyString(), org.mockito.ArgumentMatchers.anyString(), org.mockito.ArgumentMatchers.anyInt(), org.mockito.ArgumentMatchers.anyString());
        }
        var before = clientState(directory);
        assertTrue(before.has("pending"));
        String upload = before.path("pending").path("upload").asText();
        var actor = new Drive.Actor("local", "bob");
        UUID ns = d.home(actor);
        assertEquals(1, ((List<?>) d.status(actor, ns, UUID.fromString(upload)).get("parts")).size());
        // Edits after staging are a second version, not a reason to mutate the durable first payload.
        java.nio.file.Files.writeString(local, "changed while disconnected");
        sync(directory, true, true);
        assertFalse(clientState(directory).has("pending"));
        assertEquals("COMPLETED", d.status(actor, ns, UUID.fromString(upload)).get("state"));
        UUID file = (UUID) d.one("SELECT node FROM upload_sessions WHERE id=?", UUID.fromString(upload)).get("node");
        assertEquals(2, d.versions(actor, ns, file).size());
        assertArrayEquals(content, HttpClient.newHttpClient().send(HttpRequest.newBuilder(URI.create(d.download(actor, ns, file, UUID.fromString(upload)).get("url").toString())).build(), HttpResponse.BodyHandlers.ofByteArray()).body());
        assertEquals("changed while disconnected", java.nio.file.Files.readString(local));
        assertEquals(0L, d.namespace(ns).get("reserved"));
    }

    @Test
    void metadataHttpIdempotencyReplaysExactResponseAndRejectsKeyReuse() throws Exception {
        String base = "http://localhost:" + port + "/api/v1", auth = "Basic " + Base64.getEncoder().encodeToString("bob:integration-password".getBytes());
        String key = UUID.randomUUID().toString(), name = "receipt-" + UUID.randomUUID();
        var mapper = new tools.jackson.databind.ObjectMapper();
        var client = HttpClient.newHttpClient();
        var create = HttpRequest.newBuilder(URI.create(base + "/folders")).header("Authorization", auth).header("Content-Type", "application/json").header("Idempotency-Key", key).POST(HttpRequest.BodyPublishers.ofString("{\"name\":\"" + name + "\"}")).build();
        var response = client.send(create, HttpResponse.BodyHandlers.ofString());
        assertEquals(200, response.statusCode());
        var replay = client.send(create, HttpResponse.BodyHandlers.ofString());
        assertEquals(mapper.readTree(response.body()), mapper.readTree(replay.body()));
        String id = mapper.readTree(response.body()).path("id").asText();
        var rename = HttpRequest.newBuilder(URI.create(base + "/files/" + id)).header("Authorization", auth).header("Content-Type", "application/json").header("Idempotency-Key", UUID.randomUUID().toString()).method("PATCH", HttpRequest.BodyPublishers.ofString("{\"expected\":1,\"action\":\"rename\",\"name\":\"renamed-" + name + "\"}")).build();
        var changed = client.send(rename, HttpResponse.BodyHandlers.ofString());
        assertEquals(200, changed.statusCode());
        assertEquals(mapper.readTree(changed.body()), mapper.readTree(client.send(rename, HttpResponse.BodyHandlers.ofString()).body()));
        // The original receipt survives later node mutations and returns its original representation.
        assertEquals(mapper.readTree(response.body()), mapper.readTree(client.send(create, HttpResponse.BodyHandlers.ofString()).body()));
        var mismatch = HttpRequest.newBuilder(URI.create(base + "/folders")).header("Authorization", auth).header("Content-Type", "application/json").header("Idempotency-Key", key).POST(HttpRequest.BodyPublishers.ofString("{\"name\":\"other\"}")).build();
        assertEquals(409, client.send(mismatch, HttpResponse.BodyHandlers.ofString()).statusCode());
    }

    @Test
    void bidirectionalNameCollisionAndRemoteFolderDeletionSurviveCursorExpiry() throws Exception {
        var actor = new Drive.Actor("local", "bob");
        UUID ns = d.home(actor);
        String token = UUID.randomUUID().toString();
        String name = "collision-" + token + ".txt", folderName = "deleted-parent-" + token, childName = "child-" + token + ".txt";
        UUID remote = put(actor, ns, null, name, "remote original".getBytes(), Rules.hash("remote original"));
        d.complete(actor, ns, remote);
        UUID parent = folder(actor, ns, null, folderName), child = put(actor, ns, parent, childName, "old child".getBytes(), Rules.hash("old child"));
        d.complete(actor, ns, child);
        var directory = mirror();
        java.nio.file.Files.writeString(directory.resolve(name), "independent local file");
        sync(directory, true, true);
        assertEquals("remote original", java.nio.file.Files.readString(directory.resolve(name)));
        var localChild = directory.resolve(folderName).resolve(childName);
        java.nio.file.Files.writeString(localChild, "offline child edit");
        d.mutate(actor, ns, parent, 1, "delete", null, null);
        new SyncRetention(d, new io.micrometer.core.instrument.simple.SimpleMeterRegistry(), 0).compact(ns, java.time.Instant.now().plusSeconds(60), 10000);
        sync(directory, true, true);
        assertFalse(java.nio.file.Files.exists(localChild));
        var conflicts = d.db.queryForList("SELECT id,name FROM file_nodes WHERE ns=? AND parent IS NULL AND (name LIKE ? OR name LIKE ?)", ns, name + ".conflict-%", childName + ".conflict-%");
        assertEquals(2, conflicts.size());
        for (var conflict : conflicts) {
            String filename = conflict.get("name").toString();
            assertEquals(filename.startsWith(name) ? "independent local file" : "offline child edit", java.nio.file.Files.readString(directory.resolve(filename)));
        }
        assertEquals(0L, d.namespace(ns).get("reserved"));
        assertFalse(clientState(directory).has("pending"));
    }

    @SuppressWarnings("unchecked")
    List<Map<String, Object>> sharedRows(Drive.Actor actor, UUID subscription, long cursor, int limit) {
        return (List<Map<String, Object>>) sharedSync.changes(actor, subscription, Long.toString(cursor), limit).get("changes");
    }

    @Test
    void sharedStreamsExcludePrivateHistoryAndTrackSubtreeMembership() throws Exception {
        var owner = user();
        var viewer = new Drive.Actor(owner.tenant(), "viewer");
        UUID ns = d.home(owner), hidden = folder(owner, ns, null, "private-ancestor"), root = folder(owner, ns, hidden, "shared");
        UUID secret = folder(owner, ns, null, "unrelated-secret"), branch = folder(owner, ns, root, "branch");
        UUID upload = put(owner, ns, branch, "shared.txt", "shared".getBytes(), Rules.hash("shared"));
        UUID file = (UUID) d.complete(owner, ns, upload).get("id");
        assertStatus(403, () -> sharedSync.create(viewer, ns, root));
        UUID grant = d.share(owner, ns, root, viewer.subject(), "VIEWER");
        UUID subscription = (UUID) sharedSync.create(viewer, ns, root).get("id");
        assertEquals(subscription, sharedSync.create(viewer, ns, root).get("id"));
        var baseline = sharedRows(viewer, subscription, 0, 500);
        assertEquals(3, baseline.size());
        String encoded = d.json.writeValueAsString(baseline);
        assertFalse(encoded.contains(hidden.toString()));
        assertFalse(encoded.contains(secret.toString()));
        assertFalse(encoded.contains("private-ancestor"));
        long cursor = ((Number) baseline.getLast().get("seq")).longValue();
        assertTrue(sharedRows(viewer, subscription, cursor, 500).isEmpty());
        assertStatus(404, () -> sharedSync.changes(new Drive.Actor("other-tenant", viewer.subject()), subscription, "0", 500));
        assertStatus(404, () -> sharedSync.changes(new Drive.Actor(owner.tenant(), "stranger"), subscription, "0", 500));
        assertStatus(400, () -> sharedSync.changes(viewer, subscription, "99999", 500));
        // One folder move must remove the entire previously visible subtree, without child mutations.
        d.mutate(owner, ns, branch, 1, "move", null, hidden);
        var removals = sharedRows(viewer, subscription, cursor, 500);
        assertEquals(2, removals.size());
        for (var row : removals) {
            assertEquals("REMOVE", row.get("action"));
            assertTrue(((tools.jackson.databind.JsonNode) row.get("payload")).path("deleted").asBoolean());
        }
        cursor = ((Number) removals.getLast().get("seq")).longValue();
        d.mutate(owner, ns, branch, 2, "move", null, root);
        var first = sharedRows(viewer, subscription, cursor, 1);
        assertEquals(1, first.size());
        var second = sharedRows(viewer, subscription, ((Number) first.getFirst().get("seq")).longValue(), 1);
        assertEquals(1, second.size());
        assertNotEquals(first.getFirst().get("seq"), second.getFirst().get("seq"));
        assertStatus(403, () -> d.download(viewer, ns, secret, null));
        long head = ((Number) second.getLast().get("seq")).longValue();
        d.mutate(owner, ns, root, 1, "delete", null, null);
        var deleted = sharedRows(viewer, subscription, head, 500);
        assertEquals(3, deleted.size());
        assertTrue(deleted.stream().allMatch(row -> row.get("action").equals("REMOVE")));
        head = ((Number) deleted.getLast().get("seq")).longValue();
        d.mutate(owner, ns, root, 2, "restore", null, null);
        assertEquals(3, sharedRows(viewer, subscription, head, 500).size());
        d.revoke(owner, ns, root, grant);
        assertStatus(403, () -> sharedSync.changes(viewer, subscription, "0", 500));
        assertStatus(403, () -> sharedSync.read(viewer, subscription));
        assertStatus(403, () -> d.download(viewer, ns, file, null));
        sharedSync.remove(viewer, subscription);
        assertEquals(0, d.db.queryForObject("SELECT count(*) FROM shared_changes WHERE subscription=?", Integer.class, subscription));
        assertStatus(404, () -> sharedSync.read(viewer, subscription));
    }

    int runSharedClient(java.nio.file.Path directory, UUID subscription) throws Exception {
        String cp = System.getProperty("surefire.test.class.path", System.getProperty("java.class.path"));
        var builder = new ProcessBuilder(System.getProperty("java.home") + "/bin/java", "--class-path", cp, "sync-client/SyncClient.java",
                "http://localhost:" + port + "/api/v1", directory.toString(), "--once", "--shared=" + subscription);
        builder.environment().put("DRIVE_AUTH", "Basic " + Base64.getEncoder().encodeToString("bob:integration-password".getBytes()));
        var output = java.nio.file.Files.createTempFile("shared-client-", ".log");
        var process = builder.redirectErrorStream(true).redirectOutput(output.toFile()).start();
        if (!process.waitFor(45, TimeUnit.SECONDS)) {
            process.destroyForcibly();
            fail("Shared client timed out: " + java.nio.file.Files.readString(output));
        }
        if (process.exitValue() != 0)
            assertTrue(java.nio.file.Files.readString(output).contains("ApiFailure"), java.nio.file.Files.readString(output));
        return process.exitValue();
    }

    @Test
    void actualSharedClientMirrorsOnlyGrantedFolderAndStopsAfterRevocation() throws Exception {
        var owner = new Drive.Actor("local", "alice");
        var viewer = new Drive.Actor("local", "bob");
        UUID ns = d.home(owner);
        String name = "share-" + UUID.randomUUID();
        UUID root = folder(owner, ns, null, name);
        UUID upload = put(owner, ns, root, "readme.txt", "shared content".getBytes(), Rules.hash("shared content"));
        UUID file = (UUID) d.complete(owner, ns, upload).get("id");
        UUID grant = d.share(owner, ns, root, viewer.subject(), "VIEWER");
        String auth = "Basic " + Base64.getEncoder().encodeToString("bob:integration-password".getBytes());
        var response = HttpClient.newHttpClient().send(HttpRequest.newBuilder(URI.create("http://localhost:" + port + "/api/v1/sync/subscriptions"))
                .header("Authorization", auth).header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(d.json.writeValueAsString(Map.of("namespace", ns, "root", root)))).build(), HttpResponse.BodyHandlers.ofString());
        assertEquals(200, response.statusCode());
        UUID subscription = UUID.fromString(d.json.readTree(response.body()).path("id").asText());
        var mirror = mirror();
        assertEquals(0, runSharedClient(mirror, subscription));
        assertEquals("shared content", java.nio.file.Files.readString(mirror.resolve(name).resolve("readme.txt")));
        var initial = clientState(mirror);
        assertEquals(0, runSharedClient(mirror, subscription));
        assertEquals(initial.path("cursor"), clientState(mirror).path("cursor"));
        d.mutate(owner, ns, file, 2, "rename", "renamed.txt", null);
        assertEquals(0, runSharedClient(mirror, subscription));
        assertFalse(java.nio.file.Files.exists(mirror.resolve(name).resolve("readme.txt")));
        assertEquals("shared content", java.nio.file.Files.readString(mirror.resolve(name).resolve("renamed.txt")));
        java.nio.file.Files.writeString(mirror.resolve(name).resolve("renamed.txt"), "offline private edit");
        d.mutate(owner, ns, file, 3, "move", null, null);
        assertEquals(0, runSharedClient(mirror, subscription));
        try (var paths = java.nio.file.Files.list(mirror.resolve(name))) {
            assertTrue(paths.anyMatch(p -> p.getFileName().toString().startsWith("renamed.txt.conflict-")));
        }
        d.revoke(owner, ns, root, grant);
        var before = java.nio.file.Files.readString(mirror.resolve(".drive-state.json"));
        assertNotEquals(0, runSharedClient(mirror, subscription));
        assertEquals(before, java.nio.file.Files.readString(mirror.resolve(".drive-state.json")));
    }

    UUID replace(Drive.Actor actor, UUID ns, UUID file, long revision, byte[] bytes) throws Exception {
        var session = d.initiate(actor, ns, UUID.randomUUID().toString(), new Drive.UploadRequest(null, "replacement", file, revision, bytes.length, Rules.hash(bytes)));
        UUID id = (UUID) session.get("id");
        sendPart(actor, ns, id, bytes);
        d.complete(actor, ns, id);
        return id;
    }

    @Test
    void objectGcHonorsCopiesRetainedVersionsTrashAndGraceBeforePhysicalDeletion() throws Exception {
        var actor = user();
        UUID ns = d.home(actor);
        byte[] bytes = "retained object".getBytes();
        UUID first = put(actor, ns, null, "original", bytes, Rules.hash(bytes));
        UUID file = (UUID) d.complete(actor, ns, first).get("id");
        UUID copy = (UUID) d.copy(actor, ns, file, null, "copy").get("id");
        String oldUrl = d.download(actor, ns, file, first).get("url").toString();
        replace(actor, ns, file, 2, "new original".getBytes());
        d.retain(actor, ns, file, 1);
        var gc = new ObjectGarbageCollector(d, new io.micrometer.core.instrument.simple.SimpleMeterRegistry(), true, 600);
        assertEquals(0, gc.mark(ns)); // The copied version still references the old key.
        d.mutate(actor, ns, copy, 1, "delete", null, null);
        assertEquals(0, gc.mark(ns)); // Trash retains bytes.
        d.mutate(actor, ns, copy, 2, "restore", null, null);
        replace(actor, ns, copy, 3, "new copy".getBytes());
        d.retain(actor, ns, copy, 1);
        assertEquals(1, gc.mark(ns));
        assertEquals(0, gc.sweep(ns));
        assertEquals(200, HttpClient.newHttpClient().send(HttpRequest.newBuilder(URI.create(oldUrl)).build(), HttpResponse.BodyHandlers.discarding()).statusCode());
        d.db.update("UPDATE garbage_objects SET marked_at=now()-interval '2 days' WHERE ns=?", ns);
        assertEquals(1, gc.sweep(ns));
        assertEquals(0, gc.sweep(ns));
        assertEquals(404, HttpClient.newHttpClient().send(HttpRequest.newBuilder(URI.create(oldUrl)).build(), HttpResponse.BodyHandlers.discarding()).statusCode());
        assertNotNull(d.download(actor, ns, file, null));
        assertNotNull(d.download(actor, ns, copy, null));
        assertEquals(0L, d.namespace(ns).get("reserved"));
    }

    @Test
    void objectGcRetriesAmbiguousDeleteAndSerializesWithVersionRestore() throws Exception {
        var actor = user();
        UUID ns = d.home(actor);
        byte[] bytes = "survives restore".getBytes();
        UUID original = put(actor, ns, null, "source", bytes, Rules.hash(bytes));
        UUID file = (UUID) d.complete(actor, ns, original).get("id");
        String key = d.one("SELECT object_key FROM upload_sessions WHERE id=?", original).get("object_key").toString();
        var gc = new ObjectGarbageCollector(d, new io.micrometer.core.instrument.simple.SimpleMeterRegistry(), true, 600);
        // A stale inventory hint must be checked again under the same lock as restoration.
        d.db.update("INSERT INTO garbage_objects(object_key,ns,marked_at) VALUES(?,?,now()-interval '2 days')", key, ns);
        var entered = new CountDownLatch(1);
        var release = new CountDownLatch(1);
        try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
            var restoration = executor.submit(() -> d.locked(actor, ns, () -> {
                d.restoreVersion(actor, ns, file, original, 2);
                entered.countDown();
                try {
                    assertTrue(release.await(10, TimeUnit.SECONDS));
                } catch (InterruptedException e) {
                    throw new RuntimeException(e);
                }
                return null;
            }));
            assertTrue(entered.await(10, TimeUnit.SECONDS));
            var sweeping = executor.submit(() -> gc.sweep(ns));
            release.countDown();
            restoration.get(15, TimeUnit.SECONDS);
            assertEquals(0, sweeping.get(15, TimeUnit.SECONDS));
        }
        assertNotNull(d.download(actor, ns, file, null));
        UUID bad = put(actor, ns, null, "aborted-object", bytes, Rules.hash("incorrect"));
        assertStatus(422, () -> d.complete(actor, ns, bad));
        d.abort(actor, ns, bad);
        String orphan = d.one("SELECT object_key FROM upload_sessions WHERE id=?", bad).get("object_key").toString();
        assertEquals(1, gc.mark(ns));
        d.db.update("UPDATE garbage_objects SET marked_at=now()-interval '2 days' WHERE object_key=?", orphan);
        org.mockito.Mockito.doAnswer(call -> {
                    call.callRealMethod();
                    throw software.amazon.awssdk.core.exception.SdkClientException.create("ambiguous deletion");
                })
                .doCallRealMethod().when(storage).delete(orphan);
        assertEquals(0, gc.sweep(ns));
        assertEquals(1, d.one("SELECT failures FROM garbage_objects WHERE object_key=?", orphan).get("failures"));
        d.db.update("UPDATE garbage_objects SET retry_at=now()-interval '1 second' WHERE object_key=?", orphan);
        assertEquals(1, gc.sweep(ns));
        assertNotNull(d.download(actor, ns, file, null));
    }

    void deleteLocalTree(java.nio.file.Path directory) throws Exception {
        try (var paths = java.nio.file.Files.walk(directory)) {
            for (var path : paths.sorted(Comparator.reverseOrder()).toList()) java.nio.file.Files.delete(path);
        }
    }

    @Test
    void guardedFolderDeleteChecksAllDescendantRevisionsAndReplaysReceipt() throws Exception {
        var actor = user();
        UUID ns = d.home(actor), root = folder(actor, ns, null, "guarded"), child = folder(actor, ns, root, "nested");
        String original = d.subtreeHash(ns, root);
        folder(actor, ns, child, "new-remote-child");
        assertStatus(412, () -> d.deleteTree(actor, ns, root, 1, original));
        assertNotNull(d.read(actor, ns, child));
        String changed = d.subtreeHash(ns, root);
        d.mutate(actor, ns, child, 1, "rename", "updated-nested", null);
        assertStatus(412, () -> d.deleteTree(actor, ns, root, 1, changed));
        assertStatus(403, () -> d.deleteTree(new Drive.Actor(actor.tenant(), "stranger"), ns, root, 1, d.subtreeHash(ns, root)));
        String baseline = d.subtreeHash(ns, root), key = UUID.randomUUID().toString();
        Object request = Arrays.asList("delete-tree", root, 1L, baseline);
        long before = ((Number) d.namespace(ns).get("seq")).longValue();
        var result = d.idempotent(actor, ns, key, request, () -> d.deleteTree(actor, ns, root, 1, baseline));
        var replay = d.idempotent(actor, ns, key, request, () -> d.deleteTree(actor, ns, root, 1, baseline));
        assertEquals(d.json.readTree(d.json.writeValueAsString(result)), d.json.readTree(d.json.writeValueAsString(replay)));
        assertEquals(before + 1, ((Number) d.namespace(ns).get("seq")).longValue());
        assertEquals(true, d.node(ns, root).get("deleted"));
        assertEquals(false, d.node(ns, child).get("deleted"));
        assertStatus(404, () -> d.read(actor, ns, child));
        d.mutate(actor, ns, root, 2, "restore", null, null);
        assertNotNull(d.read(actor, ns, child));
        assertStatus(409, () -> d.idempotent(actor, ns, key, Arrays.asList("delete-tree", root, 3L, d.subtreeHash(ns, root)), () -> d.deleteTree(actor, ns, root, 3, d.subtreeHash(ns, root))));
    }

    @Test
    void guardedFolderDeleteRacesDescendantCreationWithoutHidingAnAcknowledgedNewNode() throws Exception {
        var actor = user();
        UUID ns = d.home(actor), root = folder(actor, ns, null, "race");
        String baseline = d.subtreeHash(ns, root);
        var start = new CountDownLatch(1);
        try (var pool = Executors.newVirtualThreadPerTaskExecutor()) {
            var deletion = pool.submit(() -> {
                start.await();
                try {
                    d.deleteTree(actor, ns, root, 1, baseline);
                    return true;
                } catch (org.springframework.web.server.ResponseStatusException e) {
                    assertEquals(412, e.getStatusCode().value());
                    return false;
                }
            });
            var creation = pool.submit(() -> {
                start.await();
                try {
                    return folder(actor, ns, root, "concurrent");
                } catch (org.springframework.web.server.ResponseStatusException e) {
                    assertEquals(409, e.getStatusCode().value());
                    return null;
                }
            });
            start.countDown();
            boolean deleted = deletion.get(10, TimeUnit.SECONDS);
            UUID created = creation.get(10, TimeUnit.SECONDS);
            assertEquals(deleted, created == null);
            if (created != null) assertNotNull(d.read(actor, ns, created));
            else assertEquals(true, d.node(ns, root).get("deleted"));
        }
    }

    @Test
    void actualClientPreservesFolderIdentityAndRejectsStaleSubtreeDeletion() throws Exception {
        var actor = new Drive.Actor("local", "bob");
        UUID ns = d.home(actor);
        String name = "folders-" + UUID.randomUUID();
        var first = mirror();
        var second = mirror();
        var source = first.resolve(name);
        java.nio.file.Files.createDirectories(source.resolve("nested/empty"));
        java.nio.file.Files.writeString(source.resolve("nested/content.txt"), "unchanged folder content");
        sync(first, true, true);
        sync(second, true, true);
        UUID root = (UUID) d.one("SELECT id FROM file_nodes WHERE ns=? AND parent IS NULL AND name=?", ns, name).get("id");
        UUID nested = (UUID) d.one("SELECT id FROM file_nodes WHERE parent=? AND name='nested'", root).get("id");
        UUID file = (UUID) d.one("SELECT id FROM file_nodes WHERE parent=? AND name='content.txt'", nested).get("id");
        var destination = first.resolve("container-" + name).resolve("renamed");
        java.nio.file.Files.createDirectories(destination.getParent());
        java.nio.file.Files.move(source, destination);
        sync(first, true, true);
        sync(second, true, true);
        assertEquals("renamed", d.read(actor, ns, root).get("name"));
        assertEquals(nested, d.one("SELECT id FROM file_nodes WHERE parent=? AND name='nested'", root).get("id"));
        assertEquals(1, d.versions(actor, ns, file).size());
        assertFalse(java.nio.file.Files.exists(second.resolve(name)));
        assertEquals("unchanged folder content", java.nio.file.Files.readString(second.resolve("container-" + name).resolve("renamed/nested/content.txt")));
        assertTrue(java.nio.file.Files.exists(destination.resolve("nested/empty/.drive-folder-id")));
        // A directory-only rename preserves even the empty descendant's identity.
        assertEquals("container-" + name + "/renamed", clientState(first).path("folderViews").path(root.toString()).path("path").asText());
        deleteLocalTree(destination);
        UUID added = folder(actor, ns, nested, "remote-addition");
        sync(first, true, true);
        assertNotNull(d.read(actor, ns, added));
        assertNotNull(d.read(actor, ns, file));
        assertTrue(java.nio.file.Files.isDirectory(destination.resolve("nested/remote-addition")));
        assertEquals("unchanged folder content", java.nio.file.Files.readString(destination.resolve("nested/content.txt")));
        // With a refreshed baseline the same local deletion becomes one ancestor tombstone.
        deleteLocalTree(destination);
        long before = ((Number) d.namespace(ns).get("seq")).longValue();
        sync(first, true, true);
        sync(second, true, true);
        assertEquals(before + 1, ((Number) d.namespace(ns).get("seq")).longValue());
        assertEquals(true, d.node(ns, root).get("deleted"));
        assertEquals(false, d.node(ns, file).get("deleted"));
        assertFalse(java.nio.file.Files.exists(second.resolve("container-" + name).resolve("renamed")));
        long revision = ((Number) d.node(ns, root).get("revision")).longValue();
        d.mutate(actor, ns, root, revision, "restore", null, null);
        sync(second, true, true);
        assertEquals("unchanged folder content", java.nio.file.Files.readString(second.resolve("container-" + name).resolve("renamed/nested/content.txt")));
    }

    @Test
    void actualClientPreservesBothTreesWhenOfflineFolderRenameConflicts() throws Exception {
        var actor = new Drive.Actor("local", "bob");
        UUID ns = d.home(actor);
        String name = "folder-conflict-" + UUID.randomUUID();
        var directory = mirror();
        java.nio.file.Files.createDirectories(directory.resolve(name));
        java.nio.file.Files.writeString(directory.resolve(name).resolve("document.txt"), "preserved bytes");
        sync(directory, true, true);
        UUID root = (UUID) d.one("SELECT id FROM file_nodes WHERE ns=? AND parent IS NULL AND name=?", ns, name).get("id");
        java.nio.file.Files.move(directory.resolve(name), directory.resolve("offline-" + name));
        d.mutate(actor, ns, root, 1, "rename", "remote-" + name, null);
        sync(directory, true, true);
        assertEquals("preserved bytes", java.nio.file.Files.readString(directory.resolve("offline-" + name).resolve("document.txt")));
        assertEquals("preserved bytes", java.nio.file.Files.readString(directory.resolve("remote-" + name).resolve("document.txt")));
        assertEquals("remote-" + name, d.read(actor, ns, root).get("name"));
        assertNotEquals(root, d.one("SELECT id FROM file_nodes WHERE ns=? AND parent IS NULL AND name=?", ns, "offline-" + name).get("id"));
        // Copying a marker with its tree must create a new identity rather than relocating the original.
        var original = directory.resolve("remote-" + name);
        var copy = directory.resolve("copy-" + name);
        try (var paths = java.nio.file.Files.walk(original)) {
            for (var source : paths.toList()) {
                var target = copy.resolve(original.relativize(source));
                if (java.nio.file.Files.isDirectory(source)) java.nio.file.Files.createDirectories(target);
                else java.nio.file.Files.copy(source, target);
            }
        }
        sync(directory, true, true);
        assertEquals("remote-" + name, d.read(actor, ns, root).get("name"));
        assertNotEquals(root, d.one("SELECT id FROM file_nodes WHERE ns=? AND parent IS NULL AND name=?", ns, "copy-" + name).get("id"));
        assertEquals("preserved bytes", java.nio.file.Files.readString(copy.resolve("document.txt")));
        // Unsupported overlapping swaps must stop before changing any remote metadata.
        long beforeSwap = ((Number) d.namespace(ns).get("seq")).longValue();
        var temporary = directory.resolve("temporary-" + name);
        java.nio.file.Files.move(original, temporary);
        java.nio.file.Files.move(copy, original);
        java.nio.file.Files.move(temporary, copy);
        sync(directory, true, false);
        assertEquals(beforeSwap, ((Number) d.namespace(ns).get("seq")).longValue());


    }

    @Test
    void physicalGcRefusesVersionedAndSuspendedBuckets() {
        String bucket = "gc-versioned-" + UUID.randomUUID();
        try (var client = S3Client.builder().endpointOverride(URI.create(endpoint())).region(Region.US_EAST_1).forcePathStyle(true)
                .credentialsProvider(StaticCredentialsProvider.create(AwsBasicCredentials.create("integration", "integration-password"))).build();
             var objects = new S3Objects(endpoint(), endpoint(), "us-east-1", bucket)) {
            client.createBucket(b -> b.bucket(bucket));
            client.putBucketVersioning(b -> b.bucket(bucket).versioningConfiguration(v -> v.status(software.amazon.awssdk.services.s3.model.BucketVersioningStatus.ENABLED)));
            client.putObject(b -> b.bucket(bucket).key("retained"), software.amazon.awssdk.core.sync.RequestBody.fromString("must survive"));
            assertStatus(503, () -> objects.delete("retained"));
            assertEquals(12L, client.headObject(b -> b.bucket(bucket).key("retained")).contentLength());
            client.putBucketVersioning(b -> b.bucket(bucket).versioningConfiguration(v -> v.status(software.amazon.awssdk.services.s3.model.BucketVersioningStatus.SUSPENDED)));
            assertStatus(503, () -> objects.delete("retained"));
            assertEquals(12L, client.headObject(b -> b.bucket(bucket).key("retained")).contentLength());
        }
    }

    @Test
    void asynchronousCompletionReleasesNamespaceLockAndFencesExpiredWorker() throws Exception {
        var a=user();UUID ns=d.home(a);byte[] bytes="async document".getBytes();String hash=Rules.hash(bytes);
        var session=d.initiate(a,ns,"async",new Drive.UploadRequest(null,"async.txt",null,0,bytes.length,hash));UUID id=(UUID)session.get("id");
        var signed=d.part(a,ns,id,1,hash);var request=HttpRequest.newBuilder(URI.create(signed.get("url").toString())).PUT(HttpRequest.BodyPublishers.ofByteArray(bytes));
        ((Map<String,List<String>>)signed.get("headers")).forEach((k,v)->{if(!k.equalsIgnoreCase("host"))v.forEach(value->request.header(k,value));});
        assertEquals(200,HttpClient.newHttpClient().send(request.build(),HttpResponse.BodyHandlers.discarding()).statusCode());
        assertEquals("COMPLETING",d.requestCompletion(a,ns,id).get("state"));
        assertEquals("COMPLETING",d.requestCompletion(a,ns,id).get("state"));
        String key=d.upload(a,ns,id).get("object_key").toString();
        var entered=new CountDownLatch(1);var release=new CountDownLatch(1);
        org.mockito.Mockito.doAnswer(call->{entered.countDown();assertTrue(release.await(10,TimeUnit.SECONDS));return call.callRealMethod();}).when(storage).verify(key,(long)bytes.length,hash);
        try(var executor=Executors.newVirtualThreadPerTaskExecutor()){
            var completing=executor.submit(()->d.complete(a,ns,id));assertTrue(entered.await(10,TimeUnit.SECONDS));
            assertNotNull(executor.submit(()->d.createFolder(a,ns,null,"while-scanning")).get(2,TimeUnit.SECONDS));
            assertEquals(0,d.db.queryForObject("SELECT count(*) FROM file_versions WHERE id=?",Integer.class,id));
            d.db.update("UPDATE upload_sessions SET completion_fence=completion_fence+1,completion_lease=now()-interval '1 second' WHERE id=?",id);
            release.countDown();assertThrows(ExecutionException.class,()->completing.get(10,TimeUnit.SECONDS));
        } finally {release.countDown();org.mockito.Mockito.doCallRealMethod().when(storage).verify(key,(long)bytes.length,hash);}
        assertEquals(0,d.db.queryForObject("SELECT count(*) FROM file_versions WHERE id=?",Integer.class,id));
        assertNotNull(d.complete(a,ns,id));assertEquals("COMPLETED",d.status(a,ns,id).get("state"));
    }

    @Test
    void boundedStorageStreamCrossesRangeBoundaries() throws Exception {
        byte[] content=new byte[17*1024*1024+11];new Random(91).nextBytes(content);
        String key="range-test/"+UUID.randomUUID();storage.put(key,content,"application/octet-stream");
        try {
            var hash=java.security.MessageDigest.getInstance("SHA-256");
            try(var stream=new java.security.DigestInputStream(storage.open(key),hash)) {
                assertEquals(content.length,stream.transferTo(java.io.OutputStream.nullOutputStream()));
            }
            assertEquals(Rules.hash(content),Base64.getEncoder().encodeToString(hash.digest()));
            storage.verify(key,content.length,Rules.hash(content));
        } finally {storage.delete(key);}
    }

    HttpResponse<String> managedRequest(String user,String method,String path,String body)throws Exception {
        var request=HttpRequest.newBuilder(URI.create("http://localhost:"+port+"/api/v1"+path)).header("Authorization","Basic "+Base64.getEncoder().encodeToString((user+":integration-password").getBytes())).header("Content-Type","application/json").method(method,body==null?HttpRequest.BodyPublishers.noBody():HttpRequest.BodyPublishers.ofString(body));
        return HttpClient.newHttpClient().send(request.build(),HttpResponse.BodyHandlers.ofString());
    }
    @Test void tenantAdministrationRequiresRoleAndCannotCrossTenants()throws Exception {
        UUID local=d.home(new Drive.Actor("local",UUID.randomUUID().toString())),other=d.home(new Drive.Actor("different-tenant",UUID.randomUUID().toString()));
        assertEquals(403,managedRequest("alice","GET","/admin/uploads",null).statusCode());
        var usage=managedRequest("admin","GET","/admin/storage/usage",null);assertEquals(200,usage.statusCode());assertFalse(usage.body().contains(other.toString()));
        assertEquals(404,managedRequest("admin","PATCH","/admin/storage/"+other+"/quota","{\"bytes\":1048576,\"expected\":107374182400}").statusCode());
        assertEquals(200,managedRequest("admin","PATCH","/admin/storage/"+local+"/quota","{\"bytes\":1048576,\"expected\":107374182400}").statusCode());
        assertEquals(412,managedRequest("admin","PATCH","/admin/storage/"+local+"/quota","{\"bytes\":2097152,\"expected\":107374182400}").statusCode());
        assertEquals(202,managedRequest("admin","POST","/admin/reconciliation/run","{}").statusCode());
        assertEquals(200,managedRequest("admin","GET","/admin/audit",null).statusCode());
    }
    @Test void admissionIsDurableBoundedAndIdempotent(){
        var actor=user();var ns=d.home(actor);int prior=d.maxActiveUploads,rate=d.sessionsPerMinute;
        try{d.maxActiveUploads=1;d.sessionsPerMinute=1;var r=new Drive.UploadRequest(null,"limited.bin",null,0,1,Rules.hash("x"));var upload=d.initiate(actor,ns,"one",r);
            assertEquals(upload.get("id"),d.initiate(actor,ns,"one",r).get("id"));
            assertStatus(429,()->d.initiate(actor,ns,"two",r));d.abort(actor,ns,(UUID)upload.get("id"));
            assertStatus(429,()->d.initiate(actor,ns,"three",r));
            assertEquals(1,d.db.queryForObject("SELECT requests FROM api_rate_windows WHERE ns=?",Integer.class,ns));
            assertEquals(0L,d.namespace(ns).get("reserved"));
        }finally{d.maxActiveUploads=prior;d.sessionsPerMinute=rate;}
    }
    @Test void shareLinkRevocationAndOwnerReprocessingAreAuthorized()throws Exception {
        var actor=new Drive.Actor("local","charlie");var ns=d.home(actor);byte[] bytes="management fixture".getBytes();UUID upload=put(actor,ns,null,"management-"+UUID.randomUUID()+".txt",bytes,Rules.hash(bytes));var file=d.complete(actor,ns,upload);String id=file.get("id").toString();
        var link=managedRequest("charlie","POST","/files/"+id+"/share-links","{\"seconds\":30}");assertEquals(200,link.statusCode());var payload=d.json.readTree(link.body());String grant=payload.path("id").asText(),token=payload.path("token").asText();
        assertEquals(404,managedRequest("bob","POST","/files/"+id+"/shares","{\"subject\":\"bob\",\"role\":\"EDITOR\"}").statusCode());
        assertEquals(0,d.db.queryForObject("SELECT count(*) FROM sharing_permissions WHERE node=?",Integer.class,UUID.fromString(id)));
        assertNotNull(d.publicDownload(token));assertEquals(404,managedRequest("bob","GET","/files/"+id+"/share-links",null).statusCode());
        assertEquals(200,managedRequest("charlie","DELETE","/files/"+id+"/share-links/"+grant,null).statusCode());assertThrows(Exception.class,()->d.publicDownload(token));
        d.db.update("UPDATE processing_jobs SET state='SUCCEEDED',fence=fence+1,lease_until=NULL WHERE source=?",upload);
        var job=d.one("SELECT id,fence FROM processing_jobs WHERE source=?",upload);
        d.db.update("INSERT INTO processed_artifacts(job,variant,source,object_key,size,sha256,processor,fence) VALUES(?,?,?,?,?,?,?,?)",job.get("id"),"stale-fixture",upload,"artifacts/stale-fixture",1,Rules.hash("x"),"old-pipeline",((Number)job.get("fence")).longValue()-1);
        var hidden=managedRequest("charlie","GET","/files/"+id+"/artifacts",null);assertEquals(200,hidden.statusCode());assertFalse(hidden.body().contains("stale-fixture"));
        assertEquals(404,managedRequest("charlie","GET","/files/"+id+"/artifacts/stale-fixture/download",null).statusCode());
        assertEquals(404,managedRequest("bob","POST","/files/"+id+"/reprocess","{}").statusCode());
        assertEquals(200,managedRequest("charlie","POST","/files/"+id+"/reprocess","{}").statusCode());
        assertEquals(1,d.db.queryForObject("SELECT count(*) FROM processing_jobs WHERE source=?",Integer.class,upload));
    }

}
