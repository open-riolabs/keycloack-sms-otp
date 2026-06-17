package net.riolabs.keycloak.otp;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.jboss.logging.Logger;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.Map;

/**
 * Thin wrapper around the external OTP service.
 *
 * <p>Two operations:
 * <ul>
 *     <li>{@link #requestOtp} — asks the external service to generate and send an OTP
 *         (e.g. via WhatsApp / SMS). The OTP is generated and stored OUTSIDE Keycloak.</li>
 *     <li>{@link #verifyOtp} — asks the external service whether a submitted code is valid.</li>
 * </ul>
 *
 * <p>Contract with the external service:
 * <pre>
 *   POST {requestUrl}
 *     { "phone": "+39...", "username": "...", "realm": "..." }
 *     -> 2xx on success
 *
 *   POST {verifyUrl}
 *     { "phone": "+39...", "username": "...", "realm": "...", "otp": "123456" }
 *     -> 2xx and body {"valid": true}  => accepted
 *     -> 2xx and body {"valid": false} => rejected
 *     -> 4xx                           => rejected
 * </pre>
 */
public class OtpHttpClient {

    private static final Logger LOG = Logger.getLogger(OtpHttpClient.class);
    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final OtpHttpConfig config;
    private final HttpClient httpClient;

    public OtpHttpClient(OtpHttpConfig config) {
        this.config = config;
        this.httpClient = HttpClient.newBuilder()
                .connectTimeout(Duration.ofMillis(config.timeoutMs()))
                .followRedirects(HttpClient.Redirect.NEVER)
                .build();
    }

    /** Triggers OTP generation + delivery on the external service. */
    public boolean requestOtp(String phone, String username, String realm) {
        String url = config.requestUrl().orElseThrow(
                () -> new IllegalStateException("OTP request URL not configured"));
        ObjectNode payload = MAPPER.createObjectNode();
        payload.put("phone", phone);
        payload.put("username", username);
        payload.put("realm", realm);

        try {
            HttpResponse<String> resp = send(url, payload);
            boolean ok = isSuccess(resp.statusCode());
            if (!ok) {
                LOG.warnf("OTP request to %s returned HTTP %d", url, resp.statusCode());
            }
            return ok;
        } catch (Exception e) {
            LOG.error("OTP request call failed", e);
            return false;
        }
    }

    /** Asks the external service to validate a submitted code. */
    public boolean verifyOtp(String phone, String username, String realm, String otp) {
        String url = config.verifyUrl().orElseThrow(
                () -> new IllegalStateException("OTP verify URL not configured"));
        ObjectNode payload = MAPPER.createObjectNode();
        payload.put("phone", phone);
        payload.put("username", username);
        payload.put("realm", realm);
        payload.put("otp", otp);

        try {
            HttpResponse<String> resp = send(url, payload);
            if (!isSuccess(resp.statusCode())) {
                LOG.debugf("OTP verify rejected with HTTP %d", resp.statusCode());
                return false;
            }
            return parseValid(resp.body());
        } catch (Exception e) {
            LOG.error("OTP verify call failed", e);
            return false;
        }
    }

    private HttpResponse<String> send(String url, ObjectNode payload) throws Exception {
        byte[] body = MAPPER.writeValueAsBytes(payload);

        HttpRequest.Builder builder = HttpRequest.newBuilder()
                .uri(URI.create(url))
                .timeout(Duration.ofMillis(config.timeoutMs()))
                .header("Content-Type", "application/json")
                .header("Accept", "application/json")
                .POST(HttpRequest.BodyPublishers.ofByteArray(body));

        config.authToken().ifPresent(token ->
                builder.header(config.authHeader(), token));

        return httpClient.send(builder.build(), HttpResponse.BodyHandlers.ofString());
    }

    /**
     * A body of {"valid": false} explicitly rejects even on 2xx. Any other 2xx
     * body (empty, or without the field) is treated as success, so a minimal
     * service that just returns 200 still works.
     */
    private boolean parseValid(String body) {
        if (body == null || body.isBlank()) {
            return true;
        }
        try {
            Map<?, ?> parsed = MAPPER.readValue(body, Map.class);
            Object valid = parsed.get("valid");
            if (valid == null) {
                return true;
            }
            return Boolean.parseBoolean(String.valueOf(valid));
        } catch (Exception e) {
            // Non-JSON 2xx body: accept.
            return true;
        }
    }

    private static boolean isSuccess(int status) {
        return status >= 200 && status < 300;
    }
}
