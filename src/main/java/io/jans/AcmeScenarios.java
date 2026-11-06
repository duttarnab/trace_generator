package io.jans;

import org.json.JSONObject;

import java.util.ArrayList;
import java.util.List;

public final class AcmeScenarios {

    public record Scenario(String action, JSONObject resource, JSONObject context) {
    }

    private AcmeScenarios() {
    }

    public static List<Scenario> build(String assignee) {
        List<Scenario> list = new ArrayList<>();
        for (int i = 1; i <= 4; i++) {
            String customer = "customer-" + i;
            JSONObject record = resource("customer-record", customer, "prod", "none");
            list.add(new Scenario(action("ReadCustomer"), record, context(customer, assignee, false, "none", false, false)));
            list.add(new Scenario(action("ReadCustomer"), record, context("customer-other", assignee, false, "none", false, false)));
            list.add(new Scenario(action("ReadCustomer"), record, context(customer, "someone-else", false, "none", false, false)));
        }
        JSONObject record = resource("customer-record", "customer-1", "prod", "none");
        list.add(new Scenario(action("ExportCustomers"), record, context("customer-1", assignee, false, "none", false, false)));
        list.add(new Scenario(action("ExportCustomers"), record, context("customer-2", assignee, true, "none", true, false)));
        list.add(new Scenario(action("ReadCustomer"), record, context("customer-1", assignee, false, "none", false, true)));
        JSONObject staging = resource("workload", "none", "staging", "billing");
        list.add(new Scenario(action("Deploy"), staging, context("none", assignee, false, "billing", false, true)));
        list.add(new Scenario(action("Deploy"), staging, context("none", assignee, false, "billing", false, false)));
        list.add(new Scenario(action("Deploy"), staging, context("none", assignee, false, "payments", false, false)));
        JSONObject prod = resource("workload", "none", "prod", "billing");
        list.add(new Scenario(action("Deploy"), prod, context("none", assignee, true, "billing", true, false)));
        list.add(new Scenario(action("Deploy"), prod, context("none", assignee, false, "billing", true, false)));
        list.add(new Scenario(action("Deploy"), prod, context("none", assignee, true, "billing", false, false)));
        return list;
    }

    public static JSONObject resource(String kind, String customer, String environment, String workload) {
        String id = kind + "-" + customer + "-" + environment + "-" + workload;
        return new JSONObject()
                .put("cedar_entity_mapping", new JSONObject().put("entity_type", "AcmeOps::Resource").put("id", id))
                .put("kind", kind)
                .put("customer", customer)
                .put("environment", environment)
                .put("workload", workload);
    }

    public static JSONObject context(String ticketCustomer, String ticketAssignee, boolean changeApproved,
            String changeWorkload, boolean humanOnCall, boolean suspicious) {
        return new JSONObject()
                .put("ticket_customer", ticketCustomer)
                .put("ticket_assignee", ticketAssignee)
                .put("change_approved", changeApproved)
                .put("change_workload", changeWorkload)
                .put("human_on_call", humanOnCall)
                .put("suspicious", suspicious);
    }

    public static String action(String name) {
        return "AcmeOps::Action::\"" + name + "\"";
    }
}
