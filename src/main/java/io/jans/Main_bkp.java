package io.jans;

import com.fasterxml.jackson.databind.JsonNode;
import io.jans.cedarling.binding.wrapper.CedarlingAdapter;
import org.json.JSONArray;
import org.json.JSONObject;
import uniffi.cedarling_uniffi.*;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

public class Main_bkp {

    private static final Path BASE = Path.of("src/main/resources/config/unsigned").toAbsolutePath();
    private static final String PRODUCER_ID = "trace-generator";
    private static final String EXECUTION_AUTHORITY = "https://gateway.example.org";
    private static final List<String> ROLES = List.of("Admin", "Teacher", "Student", "Auditor", "Editor", "Viewer",
            "Manager", "Guest", "Contractor", "Support", "HR", "Finance", "Developer", "SecurityOfficer", "Intern");
    private static final List<String> APPLICATION_ACTIONS = List.of("Read", "Write", "Execute", "Share");
    private static final List<String> DOCUMENT_ACTIONS = List.of("Read", "Write");
    private static final int RESOURCES_PER_TYPE = 4;

    public static void main(String[] args) throws Exception {
        LockConfig config = LockConfig.fromEnv();
        LockClient lock = new LockClient(config);

        String runId = UUID.randomUUID().toString().substring(0, 8);
        String kid = PRODUCER_ID + "-kid-" + runId;

        String adminToken = lock.token(LockConfig.SCOPE_ADMIN);
        String writeToken = lock.token(LockConfig.SCOPE_WRITE);
        String readToken = lock.token(LockConfig.SCOPE_READONLY);

        TraceSigningKey key = TraceSigningKey.generate();
        lock.registerProducerKey(PRODUCER_ID, kid, key, adminToken);
        lock.registerProducerChain(PRODUCER_ID, PRODUCER_ID + "-instance-" + runId,
                PRODUCER_ID + "-chain-" + runId, adminToken);

        Path keyFile = writeKeyFile(key);
        JSONObject bootstrap = bootstrap(config, adminToken, keyFile, kid);

        try (CedarlingAdapter adapter = new CedarlingAdapter()) {
            adapter.loadFromJson(bootstrap.toString());

            for (String role : ROLES) {
                runBatch(adapter, lock, readToken, role, runId);
            }
        } catch (CedarlingException e) {
            fail("init failed: " + e);
        } catch (AuthorizeException | EntityException e) {
            fail("authorize failed: " + e);
        }
    }

    private static void runBatch(CedarlingAdapter adapter, LockClient lock, String readToken, String role,
            String runId) throws Exception {
        String executionId = PRODUCER_ID + "-exec-" + role + "-" + runId;
        String workloadId = "workload-" + role.toLowerCase();

        List<BatchItem> items = new ArrayList<>();
        for (int i = 1; i <= RESOURCES_PER_TYPE; i++) {
            for (String action : APPLICATION_ACTIONS) {
                items.add(adapter.batchItemFromJson(application("app-" + i), action(action), null));
            }
            for (String action : DOCUMENT_ACTIONS) {
                items.add(adapter.batchItemFromJson(document("doc-" + i), action(action), null));
            }
        }

        AuthorizeOptions options = new AuthorizeOptions(new TraceContext(
                executionId, EXECUTION_AUTHORITY, workloadId, workloadId + "-1"));
        BatchAuthorizeUnsignedResponse response = adapter.authorizeUnsignedBatch(principal(role), items, options);

        long allowed = response.getResults().stream()
                .filter(CedarlingAdapter::isOk)
                .filter(o -> CedarlingAdapter.unwrap(o).getDecision())
                .count();
        System.out.printf("role=%s workload=%s items=%d allowed=%d%n",
                role, workloadId, items.size(), allowed);

        JsonNode stored = lock.awaitExecution(executionId, EXECUTION_AUTHORITY, readToken, Duration.ofSeconds(60));
        int records = stored == null ? 0 : stored.path("records").size();
        System.out.printf("  lock records for %s: %d%n", executionId, records);
    }

    private static String principal(String role) {
        return new JSONObject()
                .put("cedar_entity_mapping", new JSONObject().put("entity_type", "Jans::User").put("id", "user-" + role))
                .put("sub", "user-" + role)
                .put("role", new JSONArray().put(role))
                .toString();
    }

    private static JSONObject application(String id) {
        return new JSONObject()
                .put("cedar_entity_mapping", new JSONObject().put("entity_type", "Jans::Application").put("id", id))
                .put("app_id", id)
                .put("name", id)
                .put("url", new JSONObject().put("host", "apps.example.org").put("path", "/" + id).put("protocol", "https"));
    }

    private static JSONObject document(String id) {
        return new JSONObject()
                .put("cedar_entity_mapping", new JSONObject().put("entity_type", "Jans::SecretDocument").put("id", id));
    }

    private static String action(String name) {
        return "Jans::Action::\"" + name + "\"";
    }

    private static JSONObject bootstrap(LockConfig config, String adminToken, Path keyFile, String kid)
            throws IOException {
        return new JSONObject(read("bootstrap.json"))
                .put("CEDARLING_POLICY_STORE_LOCAL_FN", BASE.resolve("policy-store-roles.cjar").toString())
                .put("CEDARLING_LOG_TYPE", "std_out")
                .put("CEDARLING_APPLICATION_NAME", "RBAC-testing")
                .put("CEDARLING_LOCK", "enabled")
                .put("CEDARLING_LOCK_SERVER_CONFIGURATION_URI", config.wellKnownUri())
                .put("CEDARLING_LOCK_ACCESS_TOKEN_JWT", adminToken)
                .put("CEDARLING_TRACE", "enabled")
                .put("CEDARLING_TRACE_PRODUCER_ID", PRODUCER_ID)
                .put("CEDARLING_TRACE_SIGNING_KEY", keyFile.toString())
                .put("CEDARLING_TRACE_SIGNING_KID", kid);
    }

    private static Path writeKeyFile(TraceSigningKey key) throws IOException {
        Path file = Files.createTempFile("trace-key-", ".pem",
                PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rw-------")));
        file.toFile().deleteOnExit();
        Files.writeString(file, key.privateKeyPem());
        return file;
    }

    private static String read(String name) throws IOException {
        return Files.readString(BASE.resolve(name));
    }

    private static void fail(String message) {
        System.err.println(message);
        System.exit(1);
    }
}
