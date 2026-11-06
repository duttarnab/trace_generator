package io.jans.facade;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;

public class FacadeMain {

    private static Instant testStart(Path output) throws IOException {
        Path run = output.resolve("run.json");
        if (!Files.exists(run)) {
            return null;
        }
        JsonNode start = new ObjectMapper().readTree(run.toFile()).path("test_start");
        return start.isTextual() ? Instant.parse(start.asText()) : null;
    }

    public static void main(String[] args) throws Exception {
        FacadeSettings settings = FacadeSettings.load();
        Path output = settings.outputDir();
        Path traces = args.length > 0 ? Path.of(args[0]).toAbsolutePath() : output.resolve("traces");
        Path truth = args.length > 1 ? Path.of(args[1]).toAbsolutePath() : output.resolve("truth.json");

        if (!Files.isDirectory(traces)) {
            throw new IllegalStateException("no traces in " + traces + ". Run io.jans.Main first to generate them.");
        }

        FacadePipeline pipeline = new FacadePipeline(settings);
        FacadePipeline.Prepared prepared = pipeline.prepare(traces, output, testStart(output));
        System.out.println(Files.readString(prepared.dir().resolve("summary.txt")));
        if (settings.runFacade()) {
            pipeline.trainAndScore(prepared, truth);
        }
    }
}
