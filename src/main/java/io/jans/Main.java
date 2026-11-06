package io.jans;

import io.jans.cedarling.binding.wrapper.CedarlingAdapter;
import io.jans.facade.FacadeSettings;
import org.json.JSONObject;
import uniffi.cedarling_uniffi.AuthorizeException;
import uniffi.cedarling_uniffi.AuthorizeOptions;
import uniffi.cedarling_uniffi.BatchAuthorizeMultiIssuerResponse;
import uniffi.cedarling_uniffi.BatchItem;
import uniffi.cedarling_uniffi.BatchItemMultiIssuerOutcome;
import uniffi.cedarling_uniffi.CedarlingException;
import uniffi.cedarling_uniffi.EntityException;
import uniffi.cedarling_uniffi.TraceContext;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;

/**
 * Generates TRACE records for policy-store-acme by running Cedarling authorizations, and saves the execution ids
 * to {@code output/executions.json}. Run {@link TraceCollectMain} to fetch the traces from Lock, then
 * {@link FleetMain} to turn them into FACADE training data.
 */
public class Main {

    private static final Path STORE_DIR = Path.of("src/main/resources/config/multiIssuer/policy-store-acme").toAbsolutePath();
    private static final boolean CLEAN = Boolean.getBoolean("output.clean");

    public static void main(String[] args) throws Exception {
        Path outDir = FacadeSettings.load().outputDir();
        AcmeInputs inputs = AcmeInputs.load(AcmeInputs.defaultPath());
        prepareOutput(outDir);

        TraceSession session = TraceSession.open();
        List<AcmePlan.Execution> plan = AcmePlan.build(session.runId(), inputs);
        int decisions = plan.stream().mapToInt(AcmePlan.Execution::expectedRecords).sum();
        System.out.printf("plan: %d executions, %d decisions%n", plan.size(), decisions);

        try (TestIssuer issuer = new TestIssuer()) {
            Path store = AcmeStore.build(STORE_DIR, issuer.issuer(), issuer.jwksUrl());
            JSONObject bootstrap = session.bootstrap(store, "INFO");
            try (CedarlingAdapter adapter = new CedarlingAdapter()) {
                adapter.loadFromJson(bootstrap.toString());
                generate(adapter, issuer, session, inputs, plan, outDir);
            }
        } catch (CedarlingException e) {
            fail("init failed: " + e);
        } catch (AuthorizeException | EntityException e) {
            fail("authorize failed: " + e);
        }
    }

    /**
     * Runs the planned traffic and records which executions were sent.
     * <ol>
     * <li>Walk the plan in order. Executions in the "test" phase mark {@code testStart}, the train/test split
     * that is written to {@code run.json}.</li>
     * <li>For each call, get the user's tokens (access, id, userinfo). They are created once per user and
     * cached.</li>
     * <li>Send the call's items as one multi-issuer batch. The trace context carries the execution id and
     * authority, and the user's workload id, so Cedarling signs one TRACE record per item and sends it to
     * Lock.</li>
     * <li>Count each outcome as allowed, denied or failed. An allowed item flagged as an anomaly is saved with
     * its request id, which equals the TRACE record id, for {@code truth.json}.</li>
     * <li>Pause after each execution ({@code pause_millis}) to spread the records over time.</li>
     * <li>Save {@code executions.json} (execution id, authority, phase, expected record count and workload ids
     * of each execution), {@code truth.json} (anomaly record ids) and {@code run.json} (test start). Traces are
     * not fetched here: {@link TraceCollectMain} does that from {@code executions.json}.</li>
     * </ol>
     */
    private static void generate(CedarlingAdapter adapter, TestIssuer issuer, TraceSession session,
            AcmeInputs inputs, List<AcmePlan.Execution> plan, Path outDir)
            throws Exception {
        Map<String, Map<String, String>> tokensByUser = new HashMap<>();
        List<FleetRunner.Anomaly> anomalies = new ArrayList<>();
        int allowed = 0;
        int denied = 0;
        int failed = 0;
        Instant testStart = null;
        List<TraceExporter.Sent> sent = new ArrayList<>();
        Instant start = Instant.now();

        for (AcmePlan.Execution execution : plan) {
            if (testStart == null && execution.phase().equals("test")) {
                testStart = Instant.now();
            }
            int expected = 0;
            List<String> workloadIds = new ArrayList<>();
            for (AcmePlan.Call call : execution.calls()) {
                Map<String, String> tokens = tokensByUser.get(call.user().workloadId());
                if (tokens == null) {
                    tokens = tokens(issuer, inputs, call.user(), session.runId());
                    tokensByUser.put(call.user().workloadId(), tokens);
                }
                BatchAuthorizeMultiIssuerResponse response = authorize(adapter, inputs, execution, call, tokens);
                expected += call.items().size();
                workloadIds.add(call.user().workloadId());
                for (int i = 0; i < call.items().size(); i++) {
                    BatchItemMultiIssuerOutcome outcome = response.getResults().get(i);
                    if (!CedarlingAdapter.isOk(outcome)) {
                        failed++;
                        continue;
                    }
                    var result = CedarlingAdapter.unwrap(outcome);
                    if (result.getDecision()) {
                        allowed++;
                    } else {
                        denied++;
                    }
                    AcmePlan.Item item = call.items().get(i);
                    if (item.anomaly() && result.getDecision()) {
                        anomalies.add(new FleetRunner.Anomaly(result.getRequestId(), call.user().workloadId(),
                                item.capability(), Instant.now().getEpochSecond()));
                    }
                }
                //break;
            }
            sent.add(new TraceExporter.Sent(execution.id(), inputs.executionAuthority(), execution.phase(), expected,
                    workloadIds));
            if (inputs.pauseMillis() > 0) {
                Thread.sleep(inputs.pauseMillis());
            }
        }
        System.out.printf("decisions: allowed=%d denied=%d failed=%d, anomalies recorded=%d%n", allowed, denied,
                failed, anomalies.size());

        TraceExporter.writeExecutions(outDir, session.runId(), TraceSession.PRODUCER_ID, sent);
        TraceExporter.writeTruth(outDir, anomalies);
        TraceExporter.writeRun(outDir, testStart == null ? Instant.now() : testStart);
        System.out.printf("run window: %s to %s%n", start, Instant.now());
        System.out.printf("saved %d executions to %s%n", sent.size(), outDir.resolve(TraceExporter.EXECUTIONS_FILE));
        System.out.println("next: run io.jans.TraceCollectMain to fetch the traces, then io.jans.FleetMain");
    }

    private static BatchAuthorizeMultiIssuerResponse authorize(CedarlingAdapter adapter, AcmeInputs inputs,
            AcmePlan.Execution execution, AcmePlan.Call call, Map<String, String> tokens) throws AuthorizeException, EntityException {
        List<BatchItem> items = new ArrayList<>();
        for (AcmePlan.Item item : call.items()) {
            System.out.printf("authorize execution=%s workload=%s%n  action:   %s%n  resource: %s%n  context:  %s%n",
                    execution.id(), call.user().workloadId(), item.action(), item.resource(), item.context());
            items.add(adapter.batchItemFromJson(item.resource(), item.action(), item.context()));
        }
        String workloadId = call.user().workloadId();
        AuthorizeOptions options = new AuthorizeOptions(new TraceContext(
                execution.id(), inputs.executionAuthority(), workloadId, workloadId + "-1"));
        return adapter.authorizeMultiIssuerBatch(tokens, items, options);
    }

    private static Map<String, String> tokens(TestIssuer issuer, AcmeInputs inputs, AcmePlan.User user, String runId) throws Exception {
        String sub = user.inum() + "-" + runId;
        Map<String, String> tokens = new LinkedHashMap<>();
        tokens.put("AcmeOps::Access_token", issuer.accessToken(sub, inputs.clientId()));
        tokens.put("AcmeOps::id_token", issuer.idToken(sub, inputs.clientId()));
        tokens.put("AcmeOps::Userinfo_token",
                issuer.userinfoToken(sub, inputs.clientId(), user.givenName(), user.inum(), user.roles()));
        return tokens;
    }

    private static void prepareOutput(Path outDir) throws IOException {
        Path traces = outDir.resolve("traces");
        boolean hasTraces = Files.isDirectory(traces) && Files.list(traces).findAny().isPresent();
        if (!hasTraces && !Files.exists(outDir.resolve(TraceExporter.EXECUTIONS_FILE))) {
            return;
        }
        if (!CLEAN) {
            throw new IllegalStateException(outDir + " already holds a previous run (traces or executions.json). Empty it, or run with -Doutput.clean=true to replace them.");
        }
        for (String name : List.of("traces", "facade", "truth.json", "run.json", TraceExporter.EXECUTIONS_FILE)) {
            delete(outDir.resolve(name));
        }
    }

    private static void delete(Path path) throws IOException {
        if (!Files.exists(path)) {
            return;
        }
        try (Stream<Path> walk = Files.walk(path)) {
            for (Path p : (Iterable<Path>) walk.sorted(Comparator.reverseOrder())::iterator) {
                Files.delete(p);
            }
        }
    }

    private static void fail(String message) {
        System.err.println(message);
        System.exit(1);
    }
}
