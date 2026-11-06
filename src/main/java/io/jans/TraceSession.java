package io.jans;

import org.json.JSONObject;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.UUID;

public final class TraceSession {

    public static final String PRODUCER_ID = "trace-generator";

    private final LockConfig config;
    private final LockClient lock;
    private final String writeToken;
    private final String readToken;
    private final Path keyFile;
    private final String kid;
    private final String runId;

    private TraceSession(LockConfig config, LockClient lock, String writeToken, String readToken, Path keyFile,
            String kid, String runId) {
        this.config = config;
        this.lock = lock;
        this.writeToken = writeToken;
        this.readToken = readToken;
        this.keyFile = keyFile;
        this.kid = kid;
        this.runId = runId;
    }

    public static TraceSession open() throws IOException, InterruptedException {
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

        Path keyFile = Files.createTempFile("trace-key-", ".pem",
                PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rw-------")));
        keyFile.toFile().deleteOnExit();
        Files.writeString(keyFile, key.privateKeyPem());
        return new TraceSession(config, lock, writeToken, readToken, keyFile, kid, runId);
    }

    public JSONObject bootstrap(Path store, String logLevel) {
        return new JSONObject()
                .put("CEDARLING_APPLICATION_NAME", PRODUCER_ID)
                .put("CEDARLING_POLICY_STORE_LOCAL_FN", store.toString())
                .put("CEDARLING_LOG_TYPE", "std_out")
                .put("CEDARLING_LOG_LEVEL", logLevel)
                .put("CEDARLING_LOCK", "enabled")
                .put("CEDARLING_LOCK_SERVER_CONFIGURATION_URI", config.wellKnownUri())
                .put("CEDARLING_LOCK_ACCESS_TOKEN_JWT", writeToken)
                .put("CEDARLING_TRACE", "enabled")
                .put("CEDARLING_TRACE_PRODUCER_ID", PRODUCER_ID)
                .put("CEDARLING_TRACE_SIGNING_KEY", keyFile.toString())
                .put("CEDARLING_TRACE_SIGNING_KID", kid);
    }

    public LockClient lock() {
        return lock;
    }

    public String readToken() {
        return readToken;
    }

    public String runId() {
        return runId;
    }
}
