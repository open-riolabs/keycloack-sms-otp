package net.riolabs.keycloak.otp;

import jakarta.ws.rs.core.MultivaluedMap;
import org.keycloak.authentication.AuthenticationFlowContext;
import org.keycloak.models.RealmModel;
import org.keycloak.models.UserModel;

import java.util.Locale;

/**
 * Immutable bundle of the user/context data sent to the external OTP service on
 * every request/verify call.
 *
 * <p>In addition to the original {@code phone}/{@code username}/{@code realm} fields,
 * the payload carries:
 * <ul>
 *     <li>{@code firstName} / {@code lastName} — taken from the Keycloak user.</li>
 *     <li>{@code locale} — the resolved realm locale (e.g. {@code "it"}) when the realm
 *         has internationalization enabled, otherwise the literal string
 *         {@code "undefined"}.</li>
 *     <li>{@code provider} — the delivery channel, resolved as
 *         <i>request parameter → authenticator config → {@code "sms"}</i>. Only
 *         {@code "whatsapp"} and {@code "sms"} are accepted.</li>
 * </ul>
 */
public record OtpRequestData(
        String phone,
        String username,
        String realm,
        String firstName,
        String lastName,
        String locale,
        String provider) {

    /** Request parameter through which a client may pick the delivery channel. */
    public static final String PARAM_PROVIDER = "provider";

    /** Sentinel emitted when the realm has no internationalization configured. */
    public static final String LOCALE_UNDEFINED = "undefined";

    /**
     * Builds the payload data from the current flow context. {@code phone} is passed in
     * because callers resolve (and cache) it themselves.
     */
    public static OtpRequestData from(AuthenticationFlowContext context,
                                      OtpHttpConfig config, String phone) {
        UserModel user = context.getUser();
        return new OtpRequestData(
                phone,
                user != null ? user.getUsername() : null,
                context.getRealm().getName(),
                user != null ? user.getFirstName() : null,
                user != null ? user.getLastName() : null,
                resolveLocale(context),
                config.resolveProvider(requestedProvider(context)));
    }

    private static String requestedProvider(AuthenticationFlowContext context) {
        MultivaluedMap<String, String> params = context.getHttpRequest().getDecodedFormParameters();
        return params != null ? params.getFirst(PARAM_PROVIDER) : null;
    }

    private static String resolveLocale(AuthenticationFlowContext context) {
        RealmModel realm = context.getRealm();
        if (!realm.isInternationalizationEnabled()) {
            return LOCALE_UNDEFINED;
        }
        Locale locale = context.getSession().getContext().resolveLocale(context.getUser());
        return locale != null ? locale.toLanguageTag() : LOCALE_UNDEFINED;
    }
}
