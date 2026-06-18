package net.riolabs.keycloak.otp;

import jakarta.ws.rs.core.MultivaluedMap;
import jakarta.ws.rs.core.Response;
import org.jboss.logging.Logger;
import org.keycloak.authentication.AuthenticationFlowContext;
import org.keycloak.authentication.AuthenticationFlowError;
import org.keycloak.authentication.authenticators.directgrant.AbstractDirectGrantAuthenticator;
import org.keycloak.models.AuthenticationExecutionModel.Requirement;
import org.keycloak.models.KeycloakSession;
import org.keycloak.models.RealmModel;
import org.keycloak.models.UserModel;
import org.keycloak.provider.ProviderConfigProperty;

import java.util.List;

/**
 * Direct Grant (OAuth Resource Owner Password Credentials, {@code grant_type=password})
 * variant of the OTP-over-HTTP authenticator.
 *
 * <p>Unlike the browser authenticator, the direct grant flow is non-interactive: each
 * token request is a single, stateless call and {@code action()} is never invoked. The
 * two-step OTP lifecycle is therefore driven purely by the presence of the {@code otp}
 * request parameter:
 *
 * <ol>
 *   <li><b>No {@code otp}</b> → the external request endpoint is called to send a code,
 *       and the call fails with HTTP 401 {@code {"error":"otp_required"}} so the client
 *       knows to resubmit. (Calling again without {@code otp} simply resends.)</li>
 *   <li><b>{@code otp} present</b> → the external verify endpoint is called; success
 *       issues tokens, failure returns {@code {"error":"invalid_grant"}}.</li>
 * </ol>
 *
 * <p>Place this after <i>Username Validation</i> in the realm's Direct Grant flow and
 * enable <i>Direct access grants</i> on the client. The user must be resolved upstream
 * (this authenticator {@link #requiresUser() requires a user}).
 *
 * <p>This class is its own factory (see {@link AbstractDirectGrantAuthenticator}); it
 * reuses {@link OtpHttpConfig} / {@link OtpHttpClient} and the config properties declared
 * by {@link OtpHttpAuthenticatorFactory}.
 */
public class OtpHttpDirectGrantAuthenticator extends AbstractDirectGrantAuthenticator {

    public static final String PROVIDER_ID = "otp-http-direct-grant";

    private static final Logger LOG = Logger.getLogger(OtpHttpDirectGrantAuthenticator.class);

    private static final String PARAM_OTP = "otp";

    private static final Requirement[] REQUIREMENT_CHOICES = {
            Requirement.REQUIRED,
            Requirement.ALTERNATIVE,
            Requirement.DISABLED
    };

    @Override
    public void authenticate(AuthenticationFlowContext context) {
        OtpHttpConfig config = OtpHttpConfig.from(context.getAuthenticatorConfig());

        if (!config.isConfigured()) {
            LOG.error("OTP HTTP endpoints are not configured (request/verify URL missing). "
                    + "Set them in the authenticator config or via OTP_REQUEST_URL / OTP_VERIFY_URL.");
            context.failure(AuthenticationFlowError.INTERNAL_ERROR,
                    errorResponse(Response.Status.INTERNAL_SERVER_ERROR.getStatusCode(),
                            "temporarily_unavailable", "OTP service is not configured."));
            return;
        }

        UserModel user = context.getUser();
        if (user == null) {
            // The direct grant flow must resolve the user first (Username Validation step).
            context.failure(AuthenticationFlowError.UNKNOWN_USER,
                    errorResponse(Response.Status.UNAUTHORIZED.getStatusCode(),
                            "invalid_grant", "Invalid user credentials."));
            return;
        }

        String phone = user.getFirstAttribute(config.phoneAttribute());
        if (phone == null || phone.isBlank()) {
            LOG.warnf("User %s has no phone number in attribute '%s'",
                    user.getUsername(), config.phoneAttribute());
            context.failure(AuthenticationFlowError.INVALID_USER,
                    errorResponse(Response.Status.UNAUTHORIZED.getStatusCode(),
                            "invalid_grant", "No phone number is associated with this account."));
            return;
        }

        MultivaluedMap<String, String> params = context.getHttpRequest().getDecodedFormParameters();
        String otp = params.getFirst(PARAM_OTP);
        OtpHttpClient client = new OtpHttpClient(config);
        String realm = context.getRealm().getName();

        if (otp == null || otp.isBlank()) {
            // Step 1: no code submitted yet → send one and tell the client to resubmit.
            boolean sent = client.requestOtp(phone, user.getUsername(), realm);
            if (!sent) {
                LOG.warnf("External OTP request endpoint did not confirm delivery for user %s",
                        user.getUsername());
            }
            context.failure(AuthenticationFlowError.INVALID_CREDENTIALS,
                    errorResponse(Response.Status.UNAUTHORIZED.getStatusCode(),
                            "otp_required",
                            "A one-time code has been sent. Resubmit the request with the 'otp' parameter."));
            return;
        }

        // Step 2: verify the submitted code.
        boolean valid = client.verifyOtp(phone, user.getUsername(), realm, otp.trim());
        if (valid) {
            context.success();
        } else {
            context.failure(AuthenticationFlowError.INVALID_CREDENTIALS,
                    errorResponse(Response.Status.UNAUTHORIZED.getStatusCode(),
                            "invalid_grant", "Invalid or expired one-time code."));
        }
    }

    @Override
    public void action(AuthenticationFlowContext context) {
        // Direct grant is single-shot; everything happens in authenticate().
    }

    @Override
    public boolean requiresUser() {
        return true;
    }

    @Override
    public boolean configuredFor(KeycloakSession session, RealmModel realm, UserModel user) {
        return true;
    }

    @Override
    public void setRequiredActions(KeycloakSession session, RealmModel realm, UserModel user) {
        // Nothing to register: OTP lifecycle is external.
    }

    @Override
    public String getId() {
        return PROVIDER_ID;
    }

    @Override
    public String getDisplayType() {
        return "OTP via External HTTP (Direct Grant)";
    }

    @Override
    public String getReferenceCategory() {
        return "otp";
    }

    @Override
    public boolean isConfigurable() {
        return true;
    }

    @Override
    public Requirement[] getRequirementChoices() {
        return REQUIREMENT_CHOICES;
    }

    @Override
    public String getHelpText() {
        return "Verifies one-time passwords through external HTTP endpoints for the direct "
                + "grant (grant_type=password) flow. A request without an 'otp' parameter sends "
                + "a code and returns 'otp_required'; resubmit with 'otp' to obtain tokens. "
                + "Endpoints can be set here or via OTP_REQUEST_URL / OTP_VERIFY_URL.";
    }

    @Override
    public List<ProviderConfigProperty> getConfigProperties() {
        return OtpHttpAuthenticatorFactory.configProperties();
    }
}
