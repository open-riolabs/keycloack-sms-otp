# Keycloak OTP HTTP SPI

A Keycloak authenticator that delegates **OTP request and verification to your own HTTP
endpoints**. Keycloak never generates or stores the code — your service does (delivery via
WhatsApp Business Calling API, SMS, voice call, e‑mail, etc.). On success, Keycloak issues
tokens through its standard pipeline, so you get a normal access token **and a real refresh
token** with no Admin‑API workarounds.

The plugin ships **two** authenticators that share one configuration:

| Display name | Provider id | Flow | Interactive? |
|---|---|---|---|
| **OTP via External HTTP Service** | `otp-http-authenticator` | Browser | Yes — renders a code‑entry form in Keycloak's native login UI |
| **OTP via External HTTP (Direct Grant)** | `otp-http-direct-grant` | Direct grant (`grant_type=password`) | No — two stateless token requests |

Tested against **Keycloak 25.0.0** (Quarkus, Java 17+). The OTP form uses Keycloak's
FreeMarker login layout, so the user never leaves the standard UI.

---

## Table of contents

- [How it works](#how-it-works)
- [Build](#build)
- [Install](#install)
- [Configuration](#configuration)
- [Your OTP service: the endpoint contract](#your-otp-service-the-endpoint-contract)
- [Browser flow setup](#browser-flow-setup)
- [Direct grant flow setup](#direct-grant-flow-setup)
- [Passwordless users (and the User Profile gotcha)](#passwordless-users-and-the-user-profile-gotcha)
- [Customizing the UI (theming)](#customizing-the-ui-theming)
- [Docker Compose example](#docker-compose-example)
- [Troubleshooting](#troubleshooting)
- [Releases](#releases)
- [Project layout](#project-layout)
- [Security notes](#security-notes)
- [License](#license)

---

## How it works

**Browser flow** — interactive, for web/SPA logins via Authorization Code (+ PKCE):

```
Browser → /auth (Authorization Code + PKCE)
   │
   ├─ [Username Form]          ← resolves the user (built-in Keycloak step)
   │
   ├─ [OTP via External HTTP]  ← THIS plugin
   │     on entry  → POST {request URL}   your service generates + sends the code
   │     on submit → POST {verify URL}    your service validates the code
   │
   └─ success → token endpoint → access token + refresh token (native)
```

**Direct grant flow** — non‑interactive, for first‑party/API clients via
`grant_type=password`. The flow is **stateless**: the step is driven entirely by the
presence of the `otp` request parameter, so it takes two token requests:

```
POST /token  (no otp)   → plugin calls {request URL}, sends code → 401 {"error":"otp_required"}
POST /token  (with otp) → plugin calls {verify URL}             → 200 access + refresh token
```

In both flows the code's lifetime, length, retry counting and rate‑limiting live entirely
in **your** service. Keycloak only relays the request/verify calls.

---

## Build

Requirements: **JDK 17+** and **Maven** (Maven Central must be reachable).

```bash
mvn clean verify
```

Output: `target/keycloak-otp-http-spi.jar` — a thin JAR (no shaded dependencies; it uses
only the JDK `HttpClient` and Keycloak‑provided libraries).

> **Version alignment.** The `keycloak.version` property in `pom.xml` must match your
> running Keycloak server (currently `25.0.0`). A JAR compiled against a different major
> can fail to load at runtime (`NoSuchMethodError`/`NoClassDefFoundError`). If you upgrade
> Keycloak, bump `keycloak.version` and rebuild.

---

## Install

Copy the JAR into Keycloak's `providers/` directory and (re)build the server:

```bash
cp target/keycloak-otp-http-spi.jar /opt/keycloak/providers/
/opt/keycloak/bin/kc.sh build
/opt/keycloak/bin/kc.sh start
```

With the official container image, bake it in at build time:

```dockerfile
FROM quay.io/keycloak/keycloak:25.0.0 AS builder
COPY keycloak-otp-http-spi.jar /opt/keycloak/providers/
RUN /opt/keycloak/bin/kc.sh build

FROM quay.io/keycloak/keycloak:25.0.0
COPY --from=builder /opt/keycloak /opt/keycloak
ENTRYPOINT ["/opt/keycloak/bin/kc.sh"]
CMD ["start", "--optimized"]
```

If instead you mount `providers/` as a volume, run `kc.sh start` **without** `--optimized`
so Keycloak re‑runs `build` on startup and picks up the JAR. See the
[Docker Compose example](#docker-compose-example).

---

## Configuration

Every setting can be provided **either** in the admin UI (Authentication → Flows → the
execution's gear icon) **or** via an environment variable. Resolution order per setting:
**UI value (if non‑blank) → environment variable → built‑in default**. Both authenticators
read the same keys.

| UI key                   | Env var                   | Default         | Required | Description                                          |
|--------------------------|---------------------------|-----------------|----------|------------------------------------------------------|
| `otp.request.url`        | `OTP_REQUEST_URL`         | —               | **Yes**  | Endpoint that generates and delivers the OTP         |
| `otp.verify.url`         | `OTP_VERIFY_URL`          | —               | **Yes**  | Endpoint that validates the submitted code           |
| `otp.auth.header`        | `OTP_AUTH_HEADER`         | `Authorization` | No       | Header used to authenticate calls to your service    |
| `otp.auth.token`         | `OTP_AUTH_TOKEN`          | —               | No       | Value for that header (e.g. `Bearer xyz`)            |
| `otp.phone.attribute`    | `OTP_PHONE_ATTRIBUTE`     | `phoneNumber`   | No       | User attribute holding the phone number              |
| `otp.http.timeout.ms`    | `OTP_HTTP_TIMEOUT_MS`     | `5000`          | No       | Connect/read timeout (ms) for calls to your service  |
| `otp.allow.registration` | `OTP_ALLOW_REGISTRATION`  | `false`         | No       | Reserved for direct‑grant auto‑provisioning          |

> Keep the auth token in the **environment variable**, not the UI field, so it never lands
> in realm exports. A blank UI field transparently falls back to the env var.

---

## Your OTP service: the endpoint contract

You must expose two `POST` endpoints. Both receive `Content-Type: application/json` and, if
`otp.auth.token` / `OTP_AUTH_TOKEN` is set, your auth header.

### Request (send the code) — `POST {request URL}`

```json
{ "phone": "+391234567890", "username": "+391234567890", "realm": "myrealm" }
```

- Your service generates, stores and delivers the code (SMS/WhatsApp/etc.).
- Return any **`2xx`** to indicate the code was accepted for delivery.
- Non‑`2xx` (or a network error) is logged as "did not confirm delivery"; in the browser
  flow the form is still shown, in the direct grant flow it still returns `otp_required`.

### Verify (validate the code) — `POST {verify URL}`

```json
{ "phone": "+391234567890", "username": "+391234567890", "realm": "myrealm", "otp": "123456" }
```

| Response | Result |
|---|---|
| `2xx` with body `{"valid": true}` | **accepted** |
| `2xx` with an empty body, or any body that is **not** `{"valid": false}` | **accepted** |
| `2xx` with body `{"valid": false}` | rejected |
| `4xx` / `5xx` / network error | rejected |

The lenient parsing means a minimal service that simply returns `200 OK` on a valid code
works out of the box; return `{"valid": false}` (or a `4xx`) to reject explicitly.

---

## Browser flow setup

1. Admin console → **Authentication → Flows**.
2. Duplicate the **browser** flow (e.g. name it `browser-otp-http`).
3. Inside the copy, edit the **forms** subflow so it contains exactly:
   - **Username Form** → **Required** (resolves the user; the username is the phone, e.g.
     `+391234567890`). Remove *Username Password Form* and the *Browser - Conditional OTP*
     subflow — they are not used (passwordless, external OTP).
   - **OTP via External HTTP Service** → **Required**.
4. The resulting structure:

   ```
   Cookie ......................... Alternative
   Identity Provider Redirector ... Alternative
   forms .......................... Alternative
      ├─ Username Form ............ Required
      └─ OTP via External HTTP Service ... Required
   ```

   Keeping the steps inside the **Alternative** `forms` subflow preserves SSO: a valid
   `Cookie` short‑circuits the login. (Putting Required steps at the top level would
   disable the cookie/SSO path.)
5. Set the OTP execution's endpoints via the **gear icon**, or leave them blank to use the
   environment variables.
6. Bind the flow: realm‑wide via the flow's **⋮ → Bind flow → Browser flow**, or per client
   via **Clients → \<client\> → Advanced → Authentication flow overrides → Browser Flow**.

The client itself needs **Standard flow** enabled and, for public clients, PKCE (`S256`).

---

## Direct grant flow setup

For first‑party/API clients that authenticate with `grant_type=password`:

1. Admin console → **Authentication → Flows** → duplicate the **direct grant** flow.
2. Keep **Username Validation** (**Required**) — it resolves the user from the `username`
   parameter. **Remove Password Validation** (passwordless).
3. Add **OTP via External HTTP (Direct Grant)** → **Required**.

   ```
   Username Validation ................. Required
   OTP via External HTTP (Direct Grant)  Required
   ```
4. Bind it as the **Direct grant flow** (realm‑wide, or per client via *Clients → Advanced →
   Authentication flow overrides → Direct Grant Flow*) and enable **Direct access grants**
   on the client.

Because the flow is stateless, the grant is **two requests**:

```bash
# 1) No otp → the plugin sends a code, responds 401 {"error":"otp_required"}
curl -X POST https://<kc-host>/realms/<realm>/protocol/openid-connect/token \
  -d grant_type=password \
  -d client_id=<client-id> -d client_secret=<client-secret> \
  -d scope=openid \
  -d username=+391234567890

# 2) Resubmit with the received code → access token + refresh token
curl -X POST https://<kc-host>/realms/<realm>/protocol/openid-connect/token \
  -d grant_type=password \
  -d client_id=<client-id> -d client_secret=<client-secret> \
  -d scope=openid \
  -d username=+391234567890 \
  -d otp=123456
```

Notes:

- The `password` parameter is **not used** by this flow and may be omitted (or sent empty).
- Drop `client_secret` for public clients.
- **Response error codes** (HTTP 401, JSON `{"error": ...}`):
  - `otp_required` — a code was sent; resubmit the same request with an `otp` parameter.
  - `invalid_grant` — wrong/expired code, or the user has no phone / could not be resolved.
  - `temporarily_unavailable` — the plugin's endpoints are not configured.
- **Resend:** repeating step 1 (a request without `otp`) sends a new code. Enforce
  resend/attempt rate‑limits in your service.

---

## Passwordless users (and the User Profile gotcha)

Create users with the phone number in the configured attribute and **no credentials**:

```http
POST /admin/realms/{realm}/users
{
  "username": "+391234567890",
  "enabled": true,
  "attributes": { "phoneNumber": ["+391234567890"] }
}
```

> **Keycloak 24+ Declarative User Profile.** By default the User Profile is enabled and
> *unmanaged* attributes are disabled, so a `phoneNumber` you set may be silently dropped
> and the plugin will report **"No phone number is associated with this account."** Fix it
> one of two ways:
>
> 1. **Declare the attribute** (recommended): *Realm settings → User profile → Create
>    attribute* → name `phoneNumber`, grant admin (and user) view/edit, save. Then set the
>    value on the user.
> 2. **Enable unmanaged attributes**: *Realm settings → User profile → Enable unmanaged
>    attributes* → `Enabled` (or `Admin edit only`).
>
> The attribute name is **case‑sensitive** and must match `otp.phone.attribute`
> (default `phoneNumber`).

---

## Customizing the UI (theming)

The JAR bundles, under `theme-resources/`, a default OTP form and message bundles that
merge into any login theme:

- `theme-resources/templates/otp-http-form.ftl` — the OTP code‑entry page (browser flow).
- `theme-resources/messages/messages_en.properties`, `messages_it.properties`.

### Message keys

Override these in your theme's `messages/messages_<locale>.properties` to change wording or
add locales:

| Key | Used for |
|---|---|
| `otpHttpTitle` | Form title/header |
| `otpHttpLabel` | Code field label |
| `otpHttpSubmit` | Submit button |
| `otpHttpResend` | Resend button |
| `otpHttpResent` | Info shown after a resend |
| `otpHttpMissing` | Error: empty submission |
| `otpHttpInvalid` | Error: wrong/expired code |
| `otpHttpNoPhone` | Error: user has no phone attribute |
| `otpHttpMisconfigured` | Error: endpoints not configured |

### Overriding the templates

A login theme can override the bundled defaults — **theme templates take precedence over a
provider's `theme-resources`**. In your custom theme (`themes/<name>/login/`):

- `otp-http-form.ftl` — restyle the OTP page. The form **must** POST to
  `${url.loginAction}` with a text field **`name="otp"`** (the code). A submit button
  **`name="resend"`** (any value) re‑triggers delivery. Everything else is yours.
- `login-username.ftl` — the page rendered by the *Username Form* step (a standard Keycloak
  template; provide your own to match your branding).

Because the bundled form uses Keycloak's `registrationLayout`, simply selecting a themed
login theme already restyles it (logo, colours, fonts) without overriding the template.

### Developing themes

Theme/template output is cached. To iterate without a full rebuild, start Keycloak with
caching off and recreate the container:

```
KC_SPI_THEME_CACHE_THEMES=false
KC_SPI_THEME_CACHE_TEMPLATES=false
KC_SPI_THEME_STATIC_MAX_AGE=-1
```

Re‑enable caching in production. Activate your theme per realm via *Realm settings → Themes
→ Login theme*.

---

## Docker Compose example

```yaml
services:
  keycloak:
    image: quay.io/keycloak/keycloak:25.0.0
    command: ["start"]            # non-optimized: re-runs build and loads providers/
    volumes:
      # Drop keycloak-otp-http-spi.jar into this host directory:
      - /srv/keycloak/providers:/opt/keycloak/providers/
      - /srv/keycloak/themes:/opt/keycloak/themes
    environment:
      KC_DB: postgres
      KC_DB_URL: jdbc:postgresql://postgres:5432/keycloak
      KC_DB_USERNAME: keycloak
      KC_DB_PASSWORD: change-me
      KC_HOSTNAME: login.example.com
      KC_HTTP_ENABLED: "true"
      KC_PROXY: edge
      KEYCLOAK_ADMIN: admin
      KEYCLOAK_ADMIN_PASSWORD: change-me
      # --- OTP via External HTTP SPI ---
      OTP_REQUEST_URL: https://api.example.com/otp/request
      OTP_VERIFY_URL:  https://api.example.com/otp/verify
      OTP_AUTH_TOKEN:  "Bearer change-me"   # in compose, escape any '$' as '$$'
      # OTP_PHONE_ATTRIBUTE: phoneNumber
      # OTP_HTTP_TIMEOUT_MS: 5000
    ports:
      - "8080:8080"
    depends_on:
      - postgres
```

**Outbound reachability matters.** Keycloak must be able to reach `OTP_REQUEST_URL` /
`OTP_VERIFY_URL` *from inside the container*, which is a different network namespace than
your host or laptop. Verify it:

```bash
docker exec keycloak bash -lc 'getent hosts api.example.com'
docker exec keycloak bash -lc 'timeout 5 bash -c "cat </dev/null >/dev/tcp/api.example.com/443" && echo OPEN || echo FAIL'
```

Common pitfalls (see [Troubleshooting](#troubleshooting)): the endpoint resolves to the
host's own public IP and the router does not support NAT hairpin (use an internal address
via `extra_hosts`); or the host has no working IPv6 while the name has an `AAAA` record (set
`JAVA_OPTS_APPEND=-Djava.net.preferIPv4Stack=true`).

---

## Troubleshooting

| Symptom | Likely cause | Fix |
|---|---|---|
| Authenticator not in the *Add step* list | JAR not loaded or build not re‑run | Confirm the JAR is in `providers/` (`docker exec keycloak ls /opt/keycloak/providers/`) and that you ran `kc.sh build` / started **without** `--optimized`. |
| **"No phone number is associated with this account."** | KC 25 User Profile drops the unmanaged `phoneNumber` | Declare the attribute or enable unmanaged attributes (see [Passwordless users](#passwordless-users-and-the-user-profile-gotcha)); attribute name is case‑sensitive. |
| Log: `HttpConnectTimeoutException: HTTP connect timed out` | Container can't reach your OTP service (TCP), not a plugin bug | Test with `getent` + `/dev/tcp` from inside the container; fix DNS/NAT/firewall/IPv6 as below. |
| Endpoint works from your laptop but not from Keycloak | Different network namespace; NAT hairpin to the host's own public IP | Point the name at the internal address: `extra_hosts: ["api.example.com:<internal-ip>"]` (TLS/SNI stay valid since the hostname is unchanged). |
| Connect timeout, name has an `AAAA` record, host has no IPv6 | JDK `HttpClient` has no Happy Eyeballs and picks IPv6 | Add `JAVA_OPTS_APPEND=-Djava.net.preferIPv4Stack=true` and recreate the container. |
| Log: "did not confirm delivery" or verify always rejected | Your service returned non‑`2xx`, or auth header/token wrong | Check `OTP_AUTH_TOKEN`/`OTP_AUTH_HEADER`; confirm your endpoints return the expected status/body (see the [contract](#your-otp-service-the-endpoint-contract)). |
| Theme/template changes not visible | Files not in the container, or theme cache | Verify the path inside the container, then `docker compose up -d --force-recreate keycloak`; in dev set the cache‑off env vars. `up -d` alone won't restart an unchanged container. |
| Provider loads but errors at runtime (`NoSuchMethodError`) | JAR built against a different Keycloak major | Align `keycloak.version` in `pom.xml` with the server and rebuild. |

---

## Releases

Releases are automated by `.github/workflows/release.yml`. On every push to the default
branch (or a manual *Run workflow*):

1. **versioning** — derives a SemVer with [GitVersion](https://gitversion.net/)
   (`GitVersion.yml`) from history and commit messages.
2. **build** — stamps the POM with that version (`mvn versions:set`), runs
   `mvn clean verify`, and uploads `keycloak-otp-http-spi-X.Y.Z.jar`.
3. **release** — creates tag `vX.Y.Z` and a GitHub Release with the JAR attached.

The version bump is driven by commit messages: `fix`/`patch` → patch, `feature`/`minor` →
minor, `breaking`/`major` → major (add `+semver: skip` to suppress a bump). No manual
tagging is needed — the workflow creates the tag.

---

## Project layout

```
pom.xml                                  Maven build (keycloak.version pinned to the server)
GitVersion.yml                           SemVer rules for the release workflow
Dockerfile                               Example: bake the JAR into a custom KC image
.github/workflows/release.yml            CI: version → build → GitHub Release
src/main/java/net/riolabs/keycloak/otp/
  OtpHttpAuthenticator.java              Browser-flow authenticator
  OtpHttpAuthenticatorFactory.java       Browser factory + shared config properties
  OtpHttpDirectGrantAuthenticator.java   Direct-grant authenticator (its own factory)
  OtpHttpClient.java                     HTTP client for the request/verify calls
  OtpHttpConfig.java                     Config resolution (UI → env → default)
src/main/resources/
  META-INF/services/org.keycloak.authentication.AuthenticatorFactory   SPI registration
  theme-resources/templates/otp-http-form.ftl                          Default OTP form
  theme-resources/messages/messages_{en,it}.properties                 Default messages
```

---

## Security notes

- The verify endpoint is an **OTP oracle** — protect it (mTLS or a service token via
  `OTP_AUTH_TOKEN`) and **rate‑limit** request/verify attempts in your service. Keycloak no
  longer counts attempts, since the code lives outside it.
- Prefer the **environment variable** for the auth token so it stays out of realm exports.
- In the direct grant flow, a request without `otp` triggers delivery; throttle it to avoid
  SMS/notification flooding.
- The browser form's resend control and any client‑side countdown are **UX only** — real
  rate limiting must be server‑side (your service).

---

## License

MIT
