package io.jans.facade;

import io.jans.facade.FacadeRows.Action;
import io.jans.facade.FacadeRows.Context;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.List;

public final class FacadePipeline {

    private static final DateTimeFormatter FACADE_TIME = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss")
            .withZone(ZoneOffset.UTC);
    private static final Path EVALUATE_SCRIPT = Path.of("scripts/evaluate_scores.py").toAbsolutePath();

    public record Prepared(Path dir, FacadeMapper.Stats stats, String trainStart, String trainEnd, String testEnd,
            FacadeTemplates.Windows windows) {
    }

    private final FacadeSettings settings;

    public FacadePipeline(FacadeSettings settings) {
        this.settings = settings;
    }

    public Prepared prepare(Path tracesDir, Path outputDir, Instant testStart) throws IOException {
        Path out = outputDir.resolve("facade");
        Files.createDirectories(out);

        List<TraceRecord> traces = TraceReader.read(tracesDir);
        FacadeMapper.Options options = new FacadeMapper.Options(settings.actionKinds(), settings.outcomes(),
                settings.peerAttrs(), settings.useReceivedAt());
        FacadeMapper.Result result = FacadeMapper.convert(traces, options);
        List<Action> actions = result.actions();
        List<Context> contexts = result.contexts();
        if (actions.isEmpty() || contexts.isEmpty()) {
            throw new IOException("no actions or contexts produced from " + tracesDir + ": " + result.stats());
        }

        long minTime = Math.min(actions.stream().mapToLong(Action::occurredAt).min().getAsLong(),
                contexts.stream().mapToLong(Context::validFrom).min().getAsLong());
        long maxTime = actions.stream().mapToLong(Action::occurredAt).max().getAsLong();
        long span = Math.max(1, maxTime - minTime);
        long split = testStart != null ? testStart.getEpochSecond() : splitTime(actions, minTime, maxTime);

        long trainSpan = split - minTime;
        long testSpan = maxTime + 1 - split;
        if (trainSpan < 2 || testSpan < 1) {
            throw new IOException("too little trace data to train: " + actions.size() + " actions over "
                    + span + "s (train " + trainSpan + "s, test " + testSpan + "s). Run io.jans.Main to completion"
                    + " (do not interrupt the export) so output/traces and output/run.json hold a full run.");
        }
        FacadeTemplates.Windows windows = windows(span, trainSpan);
        FacadeFiles.writeActionsJsonl(out.resolve("actions.jsonl"), actions);
        FacadeFiles.writeContextsJsonl(out.resolve("contexts.jsonl"), contexts);
        FacadeFiles.writeActionTfRecord(out.resolve("action.tfrecord"), actions);
        FacadeFiles.writeContextTfRecord(out.resolve("context.tfrecord"), contexts);
        Files.writeString(out.resolve("directive.textproto"), FacadeTemplates.directive(settings.peerAttrs(), windows));
        Files.writeString(out.resolve("config.textproto"),
                FacadeTemplates.config(settings.peerAttrs(), settings.trainingExamples()));

        Prepared prepared = new Prepared(out, result.stats(), format(minTime), format(split), format(maxTime + 1),
                windows);
        Files.writeString(out.resolve("summary.txt"), summary(prepared));
        return prepared;
    }

    public void trainAndScore(Prepared prepared, Path truth) throws IOException, InterruptedException {
        Path facadeDir = settings.facadeDir();
        FacadeCommands commands = new FacadeCommands(facadeDir, settings.python(facadeDir),
                prepared.dir().resolve("logs"));
        commands.datasetMakerTrain(prepared.dir(), prepared.trainStart(), prepared.trainEnd());
        commands.train(prepared.dir());
        commands.inference(prepared.dir(), prepared.trainEnd(), prepared.testEnd());
        commands.readScores(prepared.dir(), 15);
        if (truth != null && Files.exists(truth)) {
            commands.evaluate(EVALUATE_SCRIPT, prepared.dir(), truth);
        }
    }

    private long splitTime(List<Action> actions, long minTime, long maxTime) {
        List<Long> times = actions.stream().map(Action::occurredAt).sorted().toList();
        String explicit = settings.testStart();
        if (!explicit.isBlank()) {
            return Instant.parse(explicit).getEpochSecond();
        }
        long byFraction = maxTime - Math.round((maxTime - minTime) * settings.testFraction());
        long byCount = times.get((int) Math.floor(times.size() * (1 - settings.testFraction())));
        return Math.max(Math.min(byFraction, byCount), minTime + 1);
    }

    private FacadeTemplates.Windows windows(long span, long trainSpan) {
        long snapshot = Math.max(1, Math.min(value("snapshotSeconds", Math.max(5, span / 60)), trainSpan / 2));
        long history = value("historySeconds", Math.max(3600, span * 4));
        long lookback = value("lookbackSeconds", history);
        long dedup = value("dedupSeconds", Math.max(1, snapshot / 10));
        return new FacadeTemplates.Windows(history, dedup, lookback, snapshot);
    }

    private long value(String name, long auto) {
        String configured = settings.windowOverride(name);
        return configured.equals("auto") ? auto : Long.parseLong(configured);
    }

    private static String format(long epochSeconds) {
        return FACADE_TIME.format(Instant.ofEpochSecond(epochSeconds));
    }

    private static String summary(Prepared p) {
        FacadeMapper.Stats s = p.stats();
        return """
                records read:          %d
                dropped unverified:    %d
                dropped void receipt:  %d
                dropped duplicates:    %d
                dropped no principal:  %d
                skipped by event kind: %d
                skipped by outcome:    %d
                actions:               %d
                contexts:              %d
                train window:          %s to %s
                test window:           %s to %s
                snapshot seconds:      %d
                history seconds:       %d
                lookback seconds:      %d
                dedup seconds:         %d
                """.formatted(s.recordsIn(), s.droppedUnverified(), s.droppedVoid(), s.droppedDuplicate(),
                s.droppedNoPrincipal(), s.skippedKind(), s.skippedOutcome(), s.actions(), s.contexts(),
                p.trainStart(), p.trainEnd(), p.trainEnd(), p.testEnd(), p.windows().snapshotSeconds(),
                p.windows().historySeconds(), p.windows().lookbackSeconds(), p.windows().dedupSeconds());
    }
}
