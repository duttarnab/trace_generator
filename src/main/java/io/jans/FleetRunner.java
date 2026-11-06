package io.jans;

import io.jans.cedarling.binding.wrapper.CedarlingAdapter;
import org.json.JSONObject;
import uniffi.cedarling_uniffi.AuthorizeException;
import uniffi.cedarling_uniffi.AuthorizeOptions;
import uniffi.cedarling_uniffi.BatchAuthorizeUnsignedResponse;
import uniffi.cedarling_uniffi.BatchItem;
import uniffi.cedarling_uniffi.BatchItemUnsignedOutcome;
import uniffi.cedarling_uniffi.EntityException;
import uniffi.cedarling_uniffi.TraceContext;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

public final class FleetRunner {

    public record Anomaly(String recordId, String principal, String resource, long signedAt) {
    }

    public record Outcome(int allowed, int denied, int failed, List<Anomaly> anomalies, Instant testStart) {
    }

    private final CedarlingAdapter adapter;
    private final long pauseMillis;

    public FleetRunner(CedarlingAdapter adapter, long pauseMillis) {
        this.adapter = adapter;
        this.pauseMillis = pauseMillis;
    }

    public Outcome run(List<FleetPlan.Execution> plan) throws AuthorizeException, EntityException,
            InterruptedException {
        int allowed = 0;
        int denied = 0;
        int failed = 0;
        Instant testStart = null;
        List<Anomaly> anomalies = new ArrayList<>();

        for (FleetPlan.Execution execution : plan) {
            if (testStart == null && execution.phase().equals("test")) {
                testStart = Instant.now();
            }
            for (FleetPlan.Call call : execution.calls()) {
                BatchAuthorizeUnsignedResponse response = authorize(execution, call);
                for (int i = 0; i < call.items().size(); i++) {
                    BatchItemUnsignedOutcome outcome = response.getResults().get(i);
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
                    FleetPlan.Item item = call.items().get(i);
                    if (item.anomaly() && result.getDecision()) {
                        anomalies.add(new Anomaly(result.getRequestId(), call.workloadId(), item.capability(),
                                Instant.now().getEpochSecond()));
                    }
                }
            }
            if (pauseMillis > 0) {
                Thread.sleep(pauseMillis);
            }
        }
        return new Outcome(allowed, denied, failed, anomalies, testStart);
    }

    private BatchAuthorizeUnsignedResponse authorize(FleetPlan.Execution execution, FleetPlan.Call call)
            throws AuthorizeException, EntityException {
        List<BatchItem> items = new ArrayList<>();
        for (FleetPlan.Item item : call.items()) {
            items.add(adapter.batchItemFromJson(resource(item), "Fleet::Action::\"" + item.action() + "\"", null));
        }
        AuthorizeOptions options = new AuthorizeOptions(new TraceContext(
                execution.id(), execution.authority(), call.workloadId(), call.workloadId() + "-1"));
        return adapter.authorizeUnsignedBatch(principal(call), items, options);
    }

    private static String principal(FleetPlan.Call call) {
        return new JSONObject()
                .put("cedar_entity_mapping", new JSONObject().put("entity_type", "Fleet::Agent").put("id", call.agent()))
                .put("team", "t" + call.team())
                .toString();
    }

    private static JSONObject resource(FleetPlan.Item item) {
        return new JSONObject()
                .put("cedar_entity_mapping", new JSONObject()
                        .put("entity_type", "Fleet::" + item.resourceType())
                        .put("id", item.resourceType().toLowerCase()))
                .put("locked", item.locked());
    }
}
