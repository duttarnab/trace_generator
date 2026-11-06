package io.jans;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.io.IOException;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.stream.Stream;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

public final class AcmeStore {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final String ISSUER_NAME = "Acme";
    private static final List<String> TOKEN_TYPES = List.of(
            "AcmeOps::Access_token", "AcmeOps::id_token", "AcmeOps::Userinfo_token");

    private AcmeStore() {
    }

    public static Path build(Path source, String iss, String jwksUrl) throws IOException {
        Path cjar = Files.createTempFile("policy-store-acme-", ".cjar");
        cjar.toFile().deleteOnExit();

        try (OutputStream out = Files.newOutputStream(cjar); ZipOutputStream zip = new ZipOutputStream(out)) {
            try (Stream<Path> files = Files.walk(source)) {
                for (Path file : (Iterable<Path>) files.filter(Files::isRegularFile)::iterator) {
                    String name = source.relativize(file).toString();
                    if (name.startsWith("trusted-issuers")) {
                        continue;
                    }
                    byte[] content = name.equals("metadata.json") ? withSpecVersion(file) : Files.readAllBytes(file);
                    put(zip, name, content);
                }
            }
            put(zip, "trusted-issuers.json", trustedIssuers(iss, jwksUrl));
        }
        return cjar;
    }

    private static byte[] withSpecVersion(Path metadata) throws IOException {
        ObjectNode node = (ObjectNode) MAPPER.readTree(metadata.toFile());
        node.put("policy_store_spec_version", 2);
        return MAPPER.writerWithDefaultPrettyPrinter().writeValueAsBytes(node);
    }

    private static byte[] trustedIssuers(String iss, String jwksUrl) throws IOException {
        ObjectNode root = MAPPER.createObjectNode();
        for (String type : TOKEN_TYPES) {
            ObjectNode entry = root.putObject(type);
            entry.put("type", "jwt");
            entry.put("issuer", ISSUER_NAME);
            ObjectNode endpoints = entry.putObject("configuration_endpoints");
            endpoints.put("jwks", jwksUrl);
            endpoints.put("iss", iss);
            entry.put("token_id", "jti");
            entry.putArray("required_claims").add("iss").add("exp").add("jti").add("sub");
        }
        return MAPPER.writerWithDefaultPrettyPrinter().writeValueAsBytes(root);
    }

    private static void put(ZipOutputStream zip, String name, byte[] content) throws IOException {
        zip.putNextEntry(new ZipEntry(name));
        zip.write(content);
        zip.closeEntry();
    }
}
