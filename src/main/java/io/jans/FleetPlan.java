package io.jans;

import java.util.ArrayList;
import java.util.List;
import java.util.Random;

public final class FleetPlan {

    public static final String PAYROLL = "PayrollExport";
    private static final List<String> SHARED = List.of("SharedWiki", "SharedCalendar");
    private static final int SERVICES_PER_TEAM = 5;
    private static final double LOCKED_RATE = 0.03;
    private static final double SHARED_RATE = 0.2;
    private static final double CROSS_TEAM_RATE = 0.1;

    public record Item(String action, String resourceType, boolean locked, boolean anomaly) {
        public String capability() {
            return "Fleet::Action::\"" + action + "\"|Fleet::" + resourceType;
        }
    }

    public record Call(String agent, int team, List<Item> items) {
        public String workloadId() {
            return "spiffe://example.org/agent/" + agent;
        }
    }

    public record Execution(String id, String authority, String phase, List<Call> calls) {
        public int expectedRecords() {
            return calls.stream().mapToInt(c -> c.items().size()).sum();
        }
    }

    private FleetPlan() {
    }

    public static List<Execution> build(String runId, long seed, int teams, int agentsPerTeam, int trainExecutions,
            int testExecutions, int anomalies) {
        Random rng = new Random(seed);
        List<Execution> plan = new ArrayList<>();
        for (int i = 0; i < trainExecutions; i++) {
            plan.add(execution(rng, "train", teams, agentsPerTeam, executionId(runId, plan.size())));
        }
        for (int i = 0; i < testExecutions; i++) {
            plan.add(execution(rng, "test", teams, agentsPerTeam, executionId(runId, plan.size())));
        }
        for (int i = 0; i < anomalies; i++) {
            plan.add(anomaly(i, agentsPerTeam, executionId(runId, plan.size())));
        }
        return plan;
    }

    private static Execution execution(Random rng, String phase, int teams, int agentsPerTeam, String id) {
        int team = rng.nextInt(teams);
        List<Integer> agents = pick(rng, agentsPerTeam, 2 + rng.nextInt(3));
        List<Call> calls = new ArrayList<>();
        for (int agent : agents) {
            calls.add(call(rng, team, agent));
        }
        if (rng.nextDouble() < CROSS_TEAM_RATE) {
            int otherTeam = (team + 1) % teams;
            calls.add(call(rng, otherTeam, rng.nextInt(agentsPerTeam)));
        }
        String authority = "spiffe://example.org/agent/" + calls.get(0).agent();
        return new Execution(id, authority, phase, calls);
    }

    private static Execution anomaly(int index, int agentsPerTeam, String id) {
        int agent = index % agentsPerTeam;
        Call call = new Call("t0-a" + agent, 0, List.of(new Item("Invoke", PAYROLL, false, true)));
        return new Execution(id, call.workloadId(), "test", List.of(call));
    }

    private static Call call(Random rng, int team, int agent) {
        int count = 1 + rng.nextInt(3);
        List<Item> items = new ArrayList<>();
        for (int i = 0; i < count; i++) {
            boolean shared = rng.nextDouble() < SHARED_RATE;
            boolean locked = rng.nextDouble() < LOCKED_RATE;
            if (shared) {
                items.add(new Item("Read", SHARED.get(rng.nextInt(SHARED.size())), locked, false));
            } else {
                items.add(new Item("Invoke", teamCapability(rng, team), locked, false));
            }
        }
        return new Call("t" + team + "-a" + agent, team, items);
    }

    private static String teamCapability(Random rng, int team) {
        if (team == 2 && rng.nextInt(SERVICES_PER_TEAM + 1) == SERVICES_PER_TEAM) {
            return PAYROLL;
        }
        return "T" + team + "Svc" + rng.nextInt(SERVICES_PER_TEAM);
    }

    private static List<Integer> pick(Random rng, int pool, int count) {
        List<Integer> all = new ArrayList<>();
        for (int i = 0; i < pool; i++) {
            all.add(i);
        }
        java.util.Collections.shuffle(all, rng);
        return all.subList(0, Math.min(count, pool));
    }

    private static String executionId(String runId, int index) {
        return "exec-" + runId + "-" + String.format("%04d", index);
    }
}
