package io.jans;

public record LockConfig(
        String lockBaseUrl,
        String authBaseUrl,
        String clientId,
        String clientSecret,
        boolean insecureTls) {

    public static final String SCOPE_WRITE = "https://jans.io/oauth/lock/trace.write";
    public static final String SCOPE_READONLY = "https://jans.io/oauth/lock/trace.readonly";
    public static final String SCOPE_ADMIN = "https://jans.io/oauth/lock/trace.admin";

    private static final String DEFAULT_BASE_URL = "https://your-server-url.com";
    private static final String DEFAULT_CLIENT_ID = "2200.e5465495-61f8-4ad2-9791-db11a3714e60";
    private static final String DEFAULT_CLIENT_SECRET = "xmPWCX5D3mSj";

    public static LockConfig fromEnv() {
        String lockBaseUrl = trimSlash(optional("LOCK_BASE_URL", DEFAULT_BASE_URL));
        return new LockConfig(
                lockBaseUrl,
                trimSlash(optional("AUTH_BASE_URL", lockBaseUrl)),
                optional("TRACE_CLIENT_ID", DEFAULT_CLIENT_ID),
                optional("TRACE_CLIENT_SECRET", DEFAULT_CLIENT_SECRET),
                Boolean.parseBoolean(optional("TRACE_INSECURE_TLS", "false")));
    }

    public String apiBase() {
        return lockBaseUrl + "/jans-lock/api/v1";
    }

    public String tokenEndpoint() {
        return authBaseUrl + "/jans-auth/restv1/token";
    }

    public String wellKnownUri() {
        return lockBaseUrl + "/.well-known/lock-server-configuration";
    }

    private static String trimSlash(String url) {
        return url.endsWith("/") ? url.substring(0, url.length() - 1) : url;
    }

    private static String required(String name) {
        String value = System.getenv(name);
        if (value == null || value.isBlank()) {
            throw new IllegalStateException("missing env var " + name);
        }
        return value;
    }

    private static String optional(String name, String fallback) {
        String value = System.getenv(name);
        return value == null || value.isBlank() ? fallback : value;
    }
}
