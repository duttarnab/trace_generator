package io.jans;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import javax.net.ssl.SSLContext;
import javax.net.ssl.TrustManager;
import javax.net.ssl.X509TrustManager;
import java.io.IOException;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.SecureRandom;
import java.security.cert.X509Certificate;
import java.time.Duration;
import java.util.Base64;

public class LockClient {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final LockConfig config;
    private final HttpClient http;

    public LockClient(LockConfig config) {
        this.config = config;
        this.http = newHttpClient(config.insecureTls());
    }

    public String token(String scope) throws IOException, InterruptedException {
        String token = getToken(scope);
        if (token == null) {
            throw new IllegalStateException("no token for scope " + scope + "; is the client granted it?");
        }
        return token;
    }

    public String getToken(String scope) throws IOException, InterruptedException {
        String body = "grant_type=client_credentials&scope=" + enc(LockConfig.SCOPE_ADMIN+"+"+LockConfig.SCOPE_WRITE+"+"+LockConfig.SCOPE_READONLY);
        String basicAuth = Base64.getEncoder().encodeToString(
                (config.clientId() + ":" + config.clientSecret()).getBytes(StandardCharsets.UTF_8));
        HttpRequest request = HttpRequest.newBuilder(URI.create(config.tokenEndpoint()))
                .header("Authorization", "Basic " + basicAuth)
                .header("Content-Type", "application/x-www-form-urlencoded")
                .POST(HttpRequest.BodyPublishers.ofString(body))
                .build();
        HttpResponse<String> response = http.send(request, HttpResponse.BodyHandlers.ofString());
        if (response.statusCode() != 200) {
            return null;
        }
        return MAPPER.readTree(response.body()).path("access_token").asText(null);
    }

    public void registerProducerKey(String producerId, String kid, TraceSigningKey key, String adminToken)
            throws IOException, InterruptedException {
        var body = MAPPER.createObjectNode();
        body.put("producer_id", producerId);
        body.put("kid", kid);
        var jwk = body.putObject("public_key_jwk");
        key.publicJwk().forEach(jwk::put);
        body.putArray("authorized_event_kinds").add("AUTHORIZATION_DECISION");
        body.put("valid_from", "2020-01-01T00:00:00Z");
        body.putNull("valid_until");
        expectCreated("key registration", post("/audit/trace/admin/producer-keys", body.toString(), adminToken));
    }

    public void registerProducerChain(String producerId, String instanceId, String chainId, String adminToken)
            throws IOException, InterruptedException {
        var body = MAPPER.createObjectNode();
        body.put("producer_id", producerId);
        body.put("producer_instance_id", instanceId);
        body.put("producer_chain_id", chainId);
        expectCreated("chain registration", post("/audit/trace/admin/producer-chains", body.toString(), adminToken));
    }

    public JsonNode awaitExecution(String executionId, String authority, String readToken, Duration timeout)
            throws IOException, InterruptedException {
        String url = config.apiBase() + "/audit/trace/executions/" + enc(executionId)
                + "?execution_authority=" + enc(authority);
        long deadline = System.nanoTime() + timeout.toNanos();
        while (System.nanoTime() < deadline) {
            HttpResponse<String> response = get(url, readToken);
            if (response.statusCode() == 200) {
                JsonNode body = MAPPER.readTree(response.body());
                if (body.path("records").size() > 0) {
                    return body;
                }
            }
            Thread.sleep(2000);
        }
        return null;
    }

    /**
     * Fetches the stored TRACE records of one execution from Lock.
     * <p>
     * Calls {@code GET /audit/trace/executions/{id}?execution_authority=...&start=0&count=1000} with the read
     * token. Lock stores records asynchronously, so the call polls every 2 seconds until at least
     * {@code expected} records are returned or {@code timeout} passes. A non-200 response is retried until
     * the timeout.
     *
     * @param executionId id of the execution, as sent in the trace context
     * @param authority   execution authority, as sent in the trace context
     * @param readToken   access token with the {@code trace.readonly} scope
     * @param expected    number of records to wait for
     * @param timeout     longest time to keep polling
     * @return the last response body. It can hold fewer than {@code expected} records after a timeout. It is an
     *         empty object if Lock never answered with 200.
     */
    public JsonNode fetchExecution(String executionId, String authority, String readToken, int expected,
            Duration timeout) throws IOException, InterruptedException {
        String url = config.apiBase() + "/audit/trace/executions/" + enc(executionId)
                + "?execution_authority=" + enc(authority) + "&start=0&count=1000";
        long deadline = System.nanoTime() + timeout.toNanos();
        JsonNode last = MAPPER.createObjectNode();
        while (true) {
            HttpResponse<String> response = get(url, readToken);
            if (response.statusCode() == 200) {
                last = MAPPER.readTree(response.body());
                if (last.path("records").size() >= expected) {
                    return last;
                }
            }
            if (System.nanoTime() >= deadline) {
                return last;
            }
            Thread.sleep(2000);
        }
    }

    private HttpResponse<String> post(String path, String json, String token)
            throws IOException, InterruptedException {
        HttpRequest request = HttpRequest.newBuilder(URI.create(config.apiBase() + path))
                .header("Content-Type", "application/json")
                .header("Authorization", "Bearer " + token)
                .POST(HttpRequest.BodyPublishers.ofString(json, StandardCharsets.UTF_8))
                .build();
        return http.send(request, HttpResponse.BodyHandlers.ofString());
    }

    private HttpResponse<String> get(String url, String token) throws IOException, InterruptedException {
        HttpRequest request = HttpRequest.newBuilder(URI.create(url))
                .header("Authorization", "Bearer " + token)
                .GET()
                .build();
        return http.send(request, HttpResponse.BodyHandlers.ofString());
    }

    private static void expectCreated(String what, HttpResponse<String> response) {
        if (response.statusCode() != 201) {
            throw new IllegalStateException(what + " failed: HTTP " + response.statusCode() + " " + response.body());
        }
    }

    private static String enc(String value) {
        return URLEncoder.encode(value, StandardCharsets.UTF_8);
    }

    private static HttpClient newHttpClient(boolean insecureTls) {
        HttpClient.Builder builder = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10));
        if (insecureTls) {
            builder.sslContext(trustAllContext());
        }
        return builder.build();
    }

    private static SSLContext trustAllContext() {
        TrustManager trustAll = new X509TrustManager() {
            @Override
            public void checkClientTrusted(X509Certificate[] chain, String authType) {
            }

            @Override
            public void checkServerTrusted(X509Certificate[] chain, String authType) {
            }

            @Override
            public X509Certificate[] getAcceptedIssuers() {
                return new X509Certificate[0];
            }
        };
        try {
            SSLContext context = SSLContext.getInstance("TLS");
            context.init(null, new TrustManager[] { trustAll }, new SecureRandom());
            return context;
        } catch (GeneralSecurityException ex) {
            throw new IllegalStateException(ex);
        }
    }
}
