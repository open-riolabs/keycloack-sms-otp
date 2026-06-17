package net.riolabs.keycloak.otp;

import org.keycloak.models.AuthenticatorConfigModel;

import java.util.Optional;

/**
 * Resolves effective authenticator configuration.
 *
 * <p>Resolution order for every setting:
 * <ol>
 *     <li>Value set in the Keycloak admin UI (authenticator config), if non-blank.</li>
 *     <li>Environment variable fallback.</li>
 *     <li>Hard-coded default (where one makes sense).</li>
 * </ol>
 *
 * <p>This lets operators keep secrets / URLs out of the realm export by using
 * environment variables, while still allowing per-flow overrides from the UI.
 */
public final class OtpHttpConfig {

    // ---- UI config keys (shown in the admin console) ----
    public static final String KEY_REQUEST_URL = "otp.request.url";
    public static final String KEY_VERIFY_URL = "otp.verify.url";
    public static final String KEY_AUTH_HEADER = "otp.auth.header";
    public static final String KEY_AUTH_TOKEN = "otp.auth.token";
    public static final String KEY_PHONE_ATTRIBUTE = "otp.phone.attribute";
    public static final String KEY_TIMEOUT_MS = "otp.http.timeout.ms";
    public static final String KEY_ALLOW_REGISTRATION = "otp.allow.registration";

    // ---- Environment variable fallbacks ----
    public static final String ENV_REQUEST_URL = "OTP_REQUEST_URL";
    public static final String ENV_VERIFY_URL = "OTP_VERIFY_URL";
    public static final String ENV_AUTH_HEADER = "OTP_AUTH_HEADER";
    public static final String ENV_AUTH_TOKEN = "OTP_AUTH_TOKEN";
    public static final String ENV_PHONE_ATTRIBUTE = "OTP_PHONE_ATTRIBUTE";
    public static final String ENV_TIMEOUT_MS = "OTP_HTTP_TIMEOUT_MS";
    public static final String ENV_ALLOW_REGISTRATION = "OTP_ALLOW_REGISTRATION";

    // ---- Defaults ----
    public static final String DEFAULT_AUTH_HEADER = "Authorization";
    public static final String DEFAULT_PHONE_ATTRIBUTE = "phoneNumber";
    public static final long DEFAULT_TIMEOUT_MS = 5000L;
    public static final boolean DEFAULT_ALLOW_REGISTRATION = false;

    private final String requestUrl;
    private final String verifyUrl;
    private final String authHeader;
    private final String authToken;
    private final String phoneAttribute;
    private final long timeoutMs;
    private final boolean allowRegistration;

    private OtpHttpConfig(String requestUrl, String verifyUrl, String authHeader,
                          String authToken, String phoneAttribute, long timeoutMs,
                          boolean allowRegistration) {
        this.requestUrl = requestUrl;
        this.verifyUrl = verifyUrl;
        this.authHeader = authHeader;
        this.authToken = authToken;
        this.phoneAttribute = phoneAttribute;
        this.timeoutMs = timeoutMs;
        this.allowRegistration = allowRegistration;
    }

    public static OtpHttpConfig from(AuthenticatorConfigModel model) {
        return new OtpHttpConfig(
                resolve(model, KEY_REQUEST_URL, ENV_REQUEST_URL, null),
                resolve(model, KEY_VERIFY_URL, ENV_VERIFY_URL, null),
                resolve(model, KEY_AUTH_HEADER, ENV_AUTH_HEADER, DEFAULT_AUTH_HEADER),
                resolve(model, KEY_AUTH_TOKEN, ENV_AUTH_TOKEN, null),
                resolve(model, KEY_PHONE_ATTRIBUTE, ENV_PHONE_ATTRIBUTE, DEFAULT_PHONE_ATTRIBUTE),
                resolveLong(model, KEY_TIMEOUT_MS, ENV_TIMEOUT_MS, DEFAULT_TIMEOUT_MS),
                resolveBool(model, KEY_ALLOW_REGISTRATION, ENV_ALLOW_REGISTRATION, DEFAULT_ALLOW_REGISTRATION)
        );
    }

    private static String resolve(AuthenticatorConfigModel model, String uiKey,
                                  String envKey, String def) {
        if (model != null && model.getConfig() != null) {
            String v = model.getConfig().get(uiKey);
            if (v != null && !v.isBlank()) {
                return v.trim();
            }
        }
        String env = System.getenv(envKey);
        if (env != null && !env.isBlank()) {
            return env.trim();
        }
        return def;
    }

    private static long resolveLong(AuthenticatorConfigModel model, String uiKey,
                                    String envKey, long def) {
        String raw = resolve(model, uiKey, envKey, null);
        if (raw == null) {
            return def;
        }
        try {
            return Long.parseLong(raw);
        } catch (NumberFormatException e) {
            return def;
        }
    }

    private static boolean resolveBool(AuthenticatorConfigModel model, String uiKey,
                                       String envKey, boolean def) {
        String raw = resolve(model, uiKey, envKey, null);
        if (raw == null) {
            return def;
        }
        return Boolean.parseBoolean(raw);
    }

    public Optional<String> requestUrl() {
        return Optional.ofNullable(requestUrl);
    }

    public Optional<String> verifyUrl() {
        return Optional.ofNullable(verifyUrl);
    }

    public boolean isConfigured() {
        return requestUrl != null && verifyUrl != null;
    }

    public String authHeader() {
        return authHeader;
    }

    public Optional<String> authToken() {
        return Optional.ofNullable(authToken);
    }

    public String phoneAttribute() {
        return phoneAttribute;
    }

    public long timeoutMs() {
        return timeoutMs;
    }

    public boolean allowRegistration() {
        return allowRegistration;
    }
}
