package io.jans.facade;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.List;
import java.util.Properties;

public final class FacadeSettings {

    private static final Path FILE = Path.of("facade.properties");

    private final Properties file = new Properties();

    private FacadeSettings() {
    }

    public static FacadeSettings load() throws IOException {
        FacadeSettings settings = new FacadeSettings();
        if (Files.exists(FILE)) {
            try (InputStream in = Files.newInputStream(FILE)) {
                settings.file.load(in);
            }
        }
        return settings;
    }

    public Path facadeDir() {
        String value = get("facade.dir", "FACADE_DIR", null);
        if (value == null || value.isBlank()) {
            throw new IllegalStateException(
                    "FACADE checkout not configured: set facade.dir in facade.properties, -Dfacade.dir or FACADE_DIR");
        }
        Path dir = Path.of(value).toAbsolutePath();
        if (!Files.isDirectory(dir.resolve("batch"))) {
            throw new IllegalStateException(dir + " is not a FACADE checkout (no batch/ directory)");
        }
        return dir;
    }

    public String python(Path facadeDir) {
        String configured = get("facade.python", "FACADE_PYTHON", null);
        if (configured != null && !configured.isBlank()) {
            return configured;
        }
        Path venv = facadeDir.resolve("env/bin/python");
        return Files.isExecutable(venv) ? venv.toString() : "python3";
    }

    public Path outputDir() {
        return Path.of(get("output.dir", null, "output")).toAbsolutePath();
    }

    public List<String> peerAttrs() {
        return Arrays.stream(get("facade.peerAttrs", null, "execution").split(","))
                .map(String::trim).filter(s -> !s.isEmpty()).toList();
    }

    public List<String> outcomes() {
        return Arrays.stream(get("facade.outcomes", null, "ALLOW,SUCCESS").split(","))
                .map(String::trim).filter(s -> !s.isEmpty()).toList();
    }

    public List<String> actionKinds() {
        return Arrays.stream(get("facade.actionKinds", null, "AUTHORIZATION_DECISION").split(","))
                .map(String::trim).filter(s -> !s.isEmpty()).toList();
    }

    public boolean useReceivedAt() {
        return get("facade.timeSource", null, "signed_at").equals("received_at");
    }

    public int trainingExamples() {
        return Integer.parseInt(get("facade.trainingExamples", null, "20000"));
    }

    public double testFraction() {
        return Double.parseDouble(get("facade.testFraction", null, "0.2"));
    }

    public String testStart() {
        return get("facade.testStart", null, "");
    }

    public String windowOverride(String name) {
        return get("facade." + name, null, "auto");
    }

    public boolean runFacade() {
        return Boolean.parseBoolean(get("facade.run", null, "true"));
    }

    private String get(String key, String env, String fallback) {
        String value = System.getProperty(key);
        if (value == null && env != null) {
            value = System.getenv(env);
        }
        if (value == null) {
            value = file.getProperty(key);
        }
        return value == null ? fallback : value;
    }
}
