package io.jans;

import io.jans.facade.FacadeSettings;

import java.nio.file.Path;
import java.time.Duration;
import java.util.List;

/**
 * Fetches the TRACE records of the executions listed in {@code output/executions.json} from Lock and stores one
 * file per execution in {@code output/traces}. Run {@link Main} before it and {@link FleetMain} after it.
 */
public class TraceCollectMain {

    private static final Duration TIMEOUT_PER_EXECUTION = Duration.ofSeconds(Long.getLong("collect.timeoutSeconds", 60L));

    public static void main(String[] args) throws Exception {
        Path outDir = FacadeSettings.load().outputDir();
        List<TraceExporter.Ref> executions = TraceExporter.readExecutions(outDir);
        System.out.printf("collecting %d executions from Lock%n", executions.size());

        LockClient lock = new LockClient(LockConfig.fromEnv());
        String readToken = lock.token(LockConfig.SCOPE_READONLY);
        TraceExporter exporter = new TraceExporter(lock, readToken, outDir);
        int missing = exporter.export(executions, TIMEOUT_PER_EXECUTION);

        System.out.printf("stored traces in %s (missing records: %d)%n", outDir.resolve("traces"), missing);
        System.out.println("next: run io.jans.FleetMain to build FACADE data and train");
    }
}
