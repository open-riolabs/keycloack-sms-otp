package net.riolabs.keycloak.otp;

import jakarta.ws.rs.core.MultivaluedMap;
import jakarta.ws.rs.core.Response;
import org.jboss.logging.Logger;
import org.keycloak.authentication.AuthenticationFlowContext;
import org.keycloak.authentication.AuthenticationFlowError;
import org.keycloak.authentication.Authenticator;
import org.keycloak.models.KeycloakSession;
import org.keycloak.models.RealmModel;
import org.keycloak.models.UserModel;

/**
 * OTP authenticator that delegates request + verification to an external HTTP service.
 *
 * <p>Designed to run as a step in the standard Keycloak browser (or direct-grant)
 * flow. The user experience is the native Keycloak login UI: the OTP entry form is
 * rendered with a standard FreeMarker template, so the user never leaves Keycloak.
 *
 * <p>Lifecycle within a single execution:
 * <ol>
 *     <li>{@link #authenticate} runs on first entry: resolves the phone number,
 *         calls the external "request" endpoint to send the code, then challenges
 *         with the OTP form.</li>
 *     <li>{@link #action} runs on form submit: reads the code, supports a "resend"
 *         action, and calls the external "verify" endpoint.</li>
 * </ol>
 */
public class OtpHttpAuthenticator implements Authenticator {

    private static final Logger LOG = Logger.getLogger(OtpHttpAuthenticator.class);

    private static final String FORM_TEMPLATE = "otp-http-form.ftl";
    private static final String FORM_FIELD_OTP = "otp";
    private static final String FORM_ACTION_RESEND = "resend";

    private static final String NOTE_OTP_SENT = "otp-http-sent";
    private static final String NOTE_PHONE = "otp-http-phone";

    @Override
    public void authenticate(AuthenticationFlowContext context) {
        OtpHttpConfig config = OtpHttpConfig.from(context.getAuthenticatorConfig());

        if (!config.isConfigured()) {
            LOG.error("OTP HTTP endpoints are not configured (request/verify URL missing). "
                    + "Set them in the authenticator config or via OTP_REQUEST_URL / OTP_VERIFY_URL.");
            context.failure(AuthenticationFlowError.INTERNAL_ERROR,
                    errorPage(context, "otpHttpMisconfigured"));
            return;
        }

        UserModel user = context.getUser();
        if (user == null) {
            // The flow must resolve the user before this step (e.g. username form).
            LOG.error("OTP HTTP authenticator requires a user in the context but none was set.");
            context.failure(AuthenticationFlowError.UNKNOWN_USER);
            return;
        }

        String phone = resolvePhone(user, config);
        if (phone == null || phone.isBlank()) {
            LOG.warnf("User %s has no phone number in attribute '%s'",
                    user.getUsername(), config.phoneAttribute());
            context.failure(AuthenticationFlowError.INVALID_USER,
                    errorPage(context, "otpHttpNoPhone"));
            return;
        }

        sendOtp(context, config, user, phone);
        context.challenge(otpForm(context).createForm(FORM_TEMPLATE));
    }

    @Override
    public void action(AuthenticationFlowContext context) {
        MultivaluedMap<String, String> form = context.getHttpRequest().getDecodedFormParameters();
        OtpHttpConfig config = OtpHttpConfig.from(context.getAuthenticatorConfig());
        UserModel user = context.getUser();
        String phone = context.getAuthenticationSession().getAuthNote(NOTE_PHONE);
        if (phone == null) {
            phone = resolvePhone(user, config);
        }

        // Resend support — re-trigger the external request endpoint.
        if (form.containsKey(FORM_ACTION_RESEND)) {
            sendOtp(context, config, user, phone);
            context.challenge(otpForm(context)
                    .setInfo("otpHttpResent")
                    .createForm(FORM_TEMPLATE));
            return;
        }

        String otp = form.getFirst(FORM_FIELD_OTP);
        if (otp == null || otp.isBlank()) {
            context.challenge(otpForm(context)
                    .setError("otpHttpMissing")
                    .createForm(FORM_TEMPLATE));
            return;
        }

        OtpHttpClient client = new OtpHttpClient(config);
        boolean valid = client.verifyOtp(
                phone, user.getUsername(), context.getRealm().getName(), otp.trim());

        if (valid) {
            context.success();
        } else {
            context.failureChallenge(AuthenticationFlowError.INVALID_CREDENTIALS,
                    otpForm(context).setError("otpHttpInvalid").createForm(FORM_TEMPLATE));
        }
    }

    private void sendOtp(AuthenticationFlowContext context, OtpHttpConfig config,
                         UserModel user, String phone) {
        OtpHttpClient client = new OtpHttpClient(config);
        boolean sent = client.requestOtp(phone, user.getUsername(),
                context.getRealm().getName());
        context.getAuthenticationSession().setAuthNote(NOTE_PHONE, phone);
        context.getAuthenticationSession().setAuthNote(NOTE_OTP_SENT, Boolean.toString(sent));
        if (!sent) {
            LOG.warnf("External OTP request endpoint did not confirm delivery for user %s",
                    user.getUsername());
        }
    }

    private String resolvePhone(UserModel user, OtpHttpConfig config) {
        if (user == null) {
            return null;
        }
        return user.getFirstAttribute(config.phoneAttribute());
    }

    private org.keycloak.forms.login.LoginFormsProvider otpForm(AuthenticationFlowContext context) {
        return context.form();
    }

    private Response errorPage(AuthenticationFlowContext context, String messageKey) {
        return context.form().setError(messageKey).createErrorPage(Response.Status.INTERNAL_SERVER_ERROR);
    }

    @Override
    public boolean requiresUser() {
        return true;
    }

    @Override
    public boolean configuredFor(KeycloakSession session, RealmModel realm, UserModel user) {
        // Eligible as long as the user has a phone attribute. We do not require any
        // stored credential, since OTP lifecycle is external.
        return true;
    }

    @Override
    public void setRequiredActions(KeycloakSession session, RealmModel realm, UserModel user) {
        // No required actions: there is nothing for the user to register up front.
    }

    @Override
    public void close() {
        // Nothing to release.
    }
}
