package io.jans;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.List;

public final class TraceExporter {

    private static final ObjectMapper MAPPER = new ObjectMapper().enable(SerializationFeature.INDENT_OUTPUT);

    private final LockClient lock;
    private final String readToken;
    private final Path tracesDir;

    public TraceExporter(LockClient lock, String readToken, Path outDir) throws IOException {
        this.lock = lock;
        this.readToken = readToken;
        this.tracesDir = outDir.resolve("traces");
        Files.createDirectories(tracesDir);
    }

    public record Ref(String id, String authority, int expectedRecords) {
    }

    public record Sent(String id, String authority, String phase, int expectedRecords, List<String> workloadIds) {
    }

    public static final String EXECUTIONS_FILE = "executions.json";

    public static void writeExecutions(Path outDir, String runId, String producerId, List<Sent> sent)
            throws IOException {
        Files.createDirectories(outDir);
        ObjectNode root = MAPPER.createObjectNode();
        root.put("run_id", runId);
        root.put("producer_id", producerId);
        root.put("written_at", Instant.now().toString());
        ArrayNode list = root.putArray("executions");
        for (Sent e : sent) {
            ObjectNode node = list.addObject();
            node.put("execution_id", e.id());
            node.put("execution_authority", e.authority());
            node.put("phase", e.phase());
            node.put("expected_records", e.expectedRecords());
            ArrayNode ids = node.putArray("workload_ids");
            e.workloadIds().forEach(ids::add);
        }
        MAPPER.writeValue(outDir.resolve(EXECUTIONS_FILE).toFile(), root);
    }

    public static List<Ref> readExecutions(Path outDir) throws IOException {
        Path file = outDir.resolve(EXECUTIONS_FILE);
        if (!Files.isRegularFile(file)) {
            throw new IllegalStateException(file + " not found. Run io.jans.Main first.");
        }
        List<Ref> refs = new java.util.ArrayList<>();
        for (JsonNode node : MAPPER.readTree(file.toFile()).path("executions")) {
            refs.add(new Ref(node.path("execution_id").asText(), node.path("execution_authority").asText(),
                    node.path("expected_records").asInt()));
        }
        return refs;
    }

    public int export(List<Ref> plan, Duration timeoutPerExecution) throws IOException, InterruptedException {
        int missing = 0;
        for (Ref execution : plan) {
            JsonNode stored = lock.fetchExecution(execution.id(), execution.authority(), readToken,
                    execution.expectedRecords(), timeoutPerExecution);
            int got = stored.path("records").size();
            if (got < execution.expectedRecords()) {
                missing += execution.expectedRecords() - got;
                System.err.printf("execution %s: %d of %d records%n", execution.id(), got, execution.expectedRecords());
            }
            MAPPER.writeValue(tracesDir.resolve(execution.id() + ".json").toFile(), stored);
        }
        return missing;
    }

    public static void writeTruth(Path outDir, List<FleetRunner.Anomaly> anomalies) throws IOException {
        ObjectNode root = MAPPER.createObjectNode();
        ArrayNode list = root.putArray("anomalies");
        for (FleetRunner.Anomaly anomaly : anomalies) {
            list.addObject()
                    .put("record_id", anomaly.recordId())
                    .put("principal", anomaly.principal())
                    .put("resource", anomaly.resource())
                    .put("signed_at", anomaly.signedAt());
        }
        MAPPER.writeValue(outDir.resolve("truth.json").toFile(), root);
    }

    public static void writeRun(Path outDir, Instant testStart) throws IOException {
        ObjectNode root = MAPPER.createObjectNode();
        root.put("test_start", testStart.toString());
        MAPPER.writeValue(outDir.resolve("run.json").toFile(), root);
    }
}
