package io.jans.facade;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

public final class FacadeCommands {

    private final Path facadeDir;
    private final String python;
    private final Path logDir;

    public FacadeCommands(Path facadeDir, String python, Path logDir) throws IOException {
        this.facadeDir = facadeDir;
        this.python = python;
        this.logDir = logDir;
        Files.createDirectories(logDir);
    }

    public void datasetMakerTrain(Path out, String start, String end) throws IOException, InterruptedException {
        run("dataset_maker_train", List.of("-m", "batch.dataset_maker_main",
                "--directive=" + out.resolve("directive.textproto"),
                "--start_time=" + start, "--end_time=" + end,
                "--action_path=" + out.resolve("action.tfrecord"),
                "--context_path=" + out.resolve("context.tfrecord"),
                "--train_output=" + out.resolve("train.tfrecord")));
    }

    public void train(Path out) throws IOException, InterruptedException {
        run("train", List.of("-m", "model.train_main",
                "--train_file=" + out.resolve("train.tfrecord"),
                "--vocabulary_file=" + out.resolve("vocabs.tfrecord"),
                "--model_config=" + out.resolve("config.textproto"),
                "--model_dir=" + out.resolve("model")));
    }

    public void inference(Path out, String start, String end) throws IOException, InterruptedException {
        run("inference", List.of("-m", "batch.inference_main",
                "--directive=" + out.resolve("directive.textproto"),
                "--start_time=" + start, "--end_time=" + end,
                "--action_path=" + out.resolve("action.tfrecord"),
                "--context_path=" + out.resolve("context.tfrecord"),
                "--output_file=" + out.resolve("scores.tfrecord"),
                "--model_config=" + out.resolve("config.textproto"),
                "--model_dir=" + out.resolve("model")));
    }

    public void readScores(Path out, int top) throws IOException, InterruptedException {
        run("read_scores", List.of("-m", "batch.read_scores_main",
                "--score_file=" + out.resolve("scores.tfrecord"), "--top_n=" + top));
    }

    public void evaluate(Path script, Path out, Path truth) throws IOException, InterruptedException {
        run("evaluate", List.of(script.toString(), facadeDir.toString(), out.resolve("scores.tfrecord").toString(),
                truth.toString()));
    }

    private void run(String name, List<String> args) throws IOException, InterruptedException {
        List<String> command = new ArrayList<>();
        command.add(python);
        command.addAll(args);
        Path log = logDir.resolve(name + ".log");
        System.out.printf("facade: %s (log %s)%n", name, log);
        ProcessBuilder builder = new ProcessBuilder(command)
                .directory(facadeDir.toFile())
                .redirectErrorStream(true)
                .redirectOutput(log.toFile());
        builder.environment().put("TF_CPP_MIN_LOG_LEVEL", "3");
        builder.environment().put("PYTHONPATH", facadeDir.toString());
        int exit = builder.start().waitFor();
        if (exit != 0) {
            throw new IOException(name + " failed with exit code " + exit + ", see " + log);
        }
    }
}
