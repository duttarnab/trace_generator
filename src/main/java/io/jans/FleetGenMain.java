package io.jans;

import io.jans.cedarling.binding.wrapper.CedarlingAdapter;
import org.json.JSONObject;
import uniffi.cedarling_uniffi.AuthorizeException;
import uniffi.cedarling_uniffi.CedarlingException;
import uniffi.cedarling_uniffi.EntityException;

import io.jans.facade.FacadePipeline;
import io.jans.facade.FacadeSettings;

import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.List;

public class FleetGenMain {

    private static final Path STORE_DIR = Path.of("src/main/resources/config/fleet/policy-store-fleet").toAbsolutePath();

    private static final long SEED = Long.getLong("fleet.seed", 7L);
    private static final int TEAMS = 3;
    private static final int AGENTS_PER_TEAM = Integer.getInteger("fleet.agents", 8);
    private static final int TRAIN_EXECUTIONS = Integer.getInteger("fleet.train", 300);
    private static final int TEST_EXECUTIONS = Integer.getInteger("fleet.test", 60);
    private static final int ANOMALIES = Integer.getInteger("fleet.anomalies", 6);
    private static final long PAUSE_MILLIS = Long.getLong("fleet.pauseMillis", 100L);

    public static void main(String[] args) throws Exception {
        FacadeSettings settings = FacadeSettings.load();
        Path outDir = settings.outputDir();
        TraceSession session = TraceSession.open();
        List<FleetPlan.Execution> plan = FleetPlan.build(session.runId(), SEED, TEAMS, AGENTS_PER_TEAM,
                TRAIN_EXECUTIONS, TEST_EXECUTIONS, ANOMALIES);
        int records = plan.stream().mapToInt(FleetPlan.Execution::expectedRecords).sum();
        System.out.printf("plan: %d executions, %d decisions%n", plan.size(), records);

        Path store = PolicyStoreZip.build(STORE_DIR);
        JSONObject bootstrap = session.bootstrap(store, "INFO");

        Instant start = Instant.now();
        FleetRunner.Outcome outcome;
        try (CedarlingAdapter adapter = new CedarlingAdapter()) {
            adapter.loadFromJson(bootstrap.toString());
            outcome = new FleetRunner(adapter, PAUSE_MILLIS).run(plan);

            System.out.printf("decisions: allowed=%d denied=%d failed=%d%n",
                    outcome.allowed(), outcome.denied(), outcome.failed());

            TraceExporter exporter = new TraceExporter(session.lock(), session.readToken(), outDir);
            int missing = exporter.export(plan.stream()
                    .map(e -> new TraceExporter.Ref(e.id(), e.authority(), e.expectedRecords())).toList(),
                    Duration.ofSeconds(60));
            exporter.writeTruth(outDir, outcome.anomalies());
            Instant end = Instant.now();

            System.out.printf("exported to %s (missing records: %d)%n", outDir, missing);
            System.out.printf("run window: %s to %s, test starts %s%n", start, end, outcome.testStart());

            FacadePipeline pipeline = new FacadePipeline(settings);
            FacadePipeline.Prepared prepared = pipeline.prepare(outDir.resolve("traces"), outDir, outcome.testStart());
            System.out.println(java.nio.file.Files.readString(prepared.dir().resolve("summary.txt")));
            if (settings.runFacade()) {
                pipeline.trainAndScore(prepared, outDir.resolve("truth.json"));
            }
        } catch (java.io.IOException e) {
            fail("pipeline failed: " + e.getMessage());
        } catch (CedarlingException e) {
            fail("init failed: " + e);
        } catch (AuthorizeException | EntityException e) {
            fail("authorize failed: " + e);
        }
    }

    private static void fail(String message) {
        System.err.println(message);
        System.exit(1);
    }
}
