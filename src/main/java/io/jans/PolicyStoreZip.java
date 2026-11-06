package io.jans;

import java.io.IOException;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.stream.Stream;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

public final class PolicyStoreZip {

    private PolicyStoreZip() {
    }

    public static Path build(Path source) throws IOException {
        Path cjar = Files.createTempFile("policy-store-", ".cjar");
        cjar.toFile().deleteOnExit();
        try (OutputStream out = Files.newOutputStream(cjar);
                ZipOutputStream zip = new ZipOutputStream(out);
                Stream<Path> files = Files.walk(source)) {
            for (Path file : (Iterable<Path>) files.filter(Files::isRegularFile)::iterator) {
                zip.putNextEntry(new ZipEntry(source.relativize(file).toString()));
                zip.write(Files.readAllBytes(file));
                zip.closeEntry();
            }
        }
        return cjar;
    }
}
