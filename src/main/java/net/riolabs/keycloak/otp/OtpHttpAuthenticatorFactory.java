package net.riolabs.keycloak.otp;

import org.keycloak.Config;
import org.keycloak.authentication.Authenticator;
import org.keycloak.authentication.AuthenticatorFactory;
import org.keycloak.models.AuthenticationExecutionModel.Requirement;
import org.keycloak.models.KeycloakSession;
import org.keycloak.models.KeycloakSessionFactory;
import org.keycloak.provider.ProviderConfigProperty;

import java.util.List;

/**
 * Factory + metadata for {@link OtpHttpAuthenticator}.
 *
 * <p>The config properties declared here render as input fields in the Keycloak
 * admin console (Authentication &gt; Flows &gt; the execution's gear icon). Any
 * field left blank falls back to the corresponding environment variable, so a
 * fully env-driven deployment can leave the UI untouched.
 */
public class OtpHttpAuthenticatorFactory implements AuthenticatorFactory {

    public static final String PROVIDER_ID = "otp-http-authenticator";

    private static final OtpHttpAuthenticator SINGLETON = new OtpHttpAuthenticator();

    private static final Requirement[] REQUIREMENT_CHOICES = {
            Requirement.REQUIRED,
            Requirement.ALTERNATIVE,
            Requirement.DISABLED
    };

    @Override
    public String getId() {
        return PROVIDER_ID;
    }

    @Override
    public String getDisplayType() {
        return "OTP via External HTTP Service";
    }

    @Override
    public String getReferenceCategory() {
        return "otp";
    }

    @Override
    public String getHelpText() {
        return "Sends and verifies one-time passwords through external HTTP endpoints. "
                + "OTP generation and validation happen outside Keycloak. Endpoints can be "
                + "set here or via environment variables (OTP_REQUEST_URL / OTP_VERIFY_URL).";
    }

    @Override
    public boolean isConfigurable() {
        return true;
    }

    @Override
    public boolean isUserSetupAllowed() {
        return false;
    }

    @Override
    public Requirement[] getRequirementChoices() {
        return REQUIREMENT_CHOICES;
    }

    @Override
    public List<ProviderConfigProperty> getConfigProperties() {
        ProviderConfigProperty requestUrl = new ProviderConfigProperty();
        requestUrl.setName(OtpHttpConfig.KEY_REQUEST_URL);
        requestUrl.setLabel("OTP request URL");
        requestUrl.setType(ProviderConfigProperty.STRING_TYPE);
        requestUrl.setHelpText("HTTP endpoint that generates and delivers the OTP "
                + "(POST with phone/username/realm). Falls back to OTP_REQUEST_URL.");

        ProviderConfigProperty verifyUrl = new ProviderConfigProperty();
        verifyUrl.setName(OtpHttpConfig.KEY_VERIFY_URL);
        verifyUrl.setLabel("OTP verify URL");
        verifyUrl.setType(ProviderConfigProperty.STRING_TYPE);
        verifyUrl.setHelpText("HTTP endpoint that validates the submitted code "
                + "(POST with phone/username/realm/otp). Falls back to OTP_VERIFY_URL.");

        ProviderConfigProperty authHeader = new ProviderConfigProperty();
        authHeader.setName(OtpHttpConfig.KEY_AUTH_HEADER);
        authHeader.setLabel("Auth header name");
        authHeader.setType(ProviderConfigProperty.STRING_TYPE);
        authHeader.setDefaultValue(OtpHttpConfig.DEFAULT_AUTH_HEADER);
        authHeader.setHelpText("Header used to authenticate calls to the OTP service. "
                + "Falls back to OTP_AUTH_HEADER. Default: Authorization.");

        ProviderConfigProperty authToken = new ProviderConfigProperty();
        authToken.setName(OtpHttpConfig.KEY_AUTH_TOKEN);
        authToken.setLabel("Auth token value");
        authToken.setType(ProviderConfigProperty.PASSWORD);
        authToken.setSecret(true);
        authToken.setHelpText("Value sent in the auth header (e.g. 'Bearer xyz'). "
                + "Falls back to OTP_AUTH_TOKEN. Prefer the env var to keep it out of realm exports.");

        ProviderConfigProperty phoneAttr = new ProviderConfigProperty();
        phoneAttr.setName(OtpHttpConfig.KEY_PHONE_ATTRIBUTE);
        phoneAttr.setLabel("Phone user attribute");
        phoneAttr.setType(ProviderConfigProperty.STRING_TYPE);
        phoneAttr.setDefaultValue(OtpHttpConfig.DEFAULT_PHONE_ATTRIBUTE);
        phoneAttr.setHelpText("User attribute holding the phone number. "
                + "Falls back to OTP_PHONE_ATTRIBUTE. Default: phoneNumber.");

        ProviderConfigProperty timeout = new ProviderConfigProperty();
        timeout.setName(OtpHttpConfig.KEY_TIMEOUT_MS);
        timeout.setLabel("HTTP timeout (ms)");
        timeout.setType(ProviderConfigProperty.STRING_TYPE);
        timeout.setDefaultValue(String.valueOf(OtpHttpConfig.DEFAULT_TIMEOUT_MS));
        timeout.setHelpText("Connect/read timeout for OTP service calls. "
                + "Falls back to OTP_HTTP_TIMEOUT_MS. Default: 5000.");

        ProviderConfigProperty allowReg = new ProviderConfigProperty();
        allowReg.setName(OtpHttpConfig.KEY_ALLOW_REGISTRATION);
        allowReg.setLabel("Allow auto-registration");
        allowReg.setType(ProviderConfigProperty.BOOLEAN_TYPE);
        allowReg.setDefaultValue("false");
        allowReg.setHelpText("Reserved for direct-grant style flows that create users on the fly. "
                + "Falls back to OTP_ALLOW_REGISTRATION.");

        return List.of(requestUrl, verifyUrl, authHeader, authToken, phoneAttr, timeout, allowReg);
    }

    @Override
    public Authenticator create(KeycloakSession session) {
        return SINGLETON;
    }

    @Override
    public void init(Config.Scope config) {
        // no-op
    }

    @Override
    public void postInit(KeycloakSessionFactory factory) {
        // no-op
    }

    @Override
    public void close() {
        // no-op
    }
}
