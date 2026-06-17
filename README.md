# Keycloak OTP HTTP SPI

A Keycloak authenticator that delegates **OTP request and verification to your own
HTTP endpoints**. Keycloak never generates or stores the code — your service does
(e.g. delivery via WhatsApp Business Calling API, SMS, etc.). On success, Keycloak
issues normal tokens through its standard flow, so you get a regular JWT **and a
real refresh token** with no Admin-API hacks.

The OTP entry form is rendered with Keycloak's native login layout (FreeMarker), so
the user stays inside the standard Keycloak UI throughout.

Tested against **Keycloak 25.0.0** (Quarkus, Java 17+).

## How it works

```
Browser → /auth (Authorization Code + PKCE)
   │
   ├─ [Username/Phone form]   ← resolves the user (built-in KC step)
   │
   ├─ [OTP via External HTTP] ← THIS plugin
   │     on entry  → POST {request URL}   your service generates + sends the code
   │     on submit → POST {verify URL}    your service validates the code
   │
   └─ success → token endpoint → access token + refresh token (native)
```

## Build

Requires JDK 17+ and Maven. (Maven Central must be reachable.)

```bash
mvn clean verify
```

Output: `target/keycloak-otp-http-spi.jar`

## Install

Copy the JAR into Keycloak's providers directory and rebuild the server:

```bash
cp target/keycloak-otp-http-spi.jar /opt/keycloak/providers/
/opt/keycloak/bin/kc.sh build
/opt/keycloak/bin/kc.sh start
```

With the official container image, add it at build time:

```dockerfile
FROM quay.io/keycloak/keycloak:25.0.0 AS builder
COPY keycloak-otp-http-spi.jar /opt/keycloak/providers/
RUN /opt/keycloak/bin/kc.sh build

FROM quay.io/keycloak/keycloak:25.0.0
COPY --from=builder /opt/keycloak /opt/keycloak
ENTRYPOINT ["/opt/keycloak/bin/kc.sh"]
```

## Configuration

Every setting can be provided **either** in the admin UI (Authentication → Flows →
the execution's gear icon) **or** via an environment variable. A blank UI field
falls back to the env var; the env var falls back to a default where one exists.

| UI key                   | Env var                 | Default       | Description                                       |
|--------------------------|-------------------------|---------------|---------------------------------------------------|
| `otp.request.url`        | `OTP_REQUEST_URL`       | —             | Endpoint that generates + sends the OTP           |
| `otp.verify.url`         | `OTP_VERIFY_URL`        | —             | Endpoint that validates the submitted code        |
| `otp.auth.header`        | `OTP_AUTH_HEADER`       | `Authorization` | Header used to authenticate calls to your service |
| `otp.auth.token`         | `OTP_AUTH_TOKEN`        | —             | Value for that header (e.g. `Bearer xyz`)         |
| `otp.phone.attribute`    | `OTP_PHONE_ATTRIBUTE`   | `phoneNumber` | User attribute holding the phone number           |
| `otp.http.timeout.ms`    | `OTP_HTTP_TIMEOUT_MS`   | `5000`        | Connect/read timeout for calls                    |
| `otp.allow.registration` | `OTP_ALLOW_REGISTRATION`| `false`       | Reserved for direct-grant auto-provisioning       |

Keep the auth token in the **env var**, not the UI, so it never lands in realm exports.

## Endpoint contract

Your service must expose two POST endpoints.

**Request** — `POST {request URL}`
```json
{ "phone": "+39...", "username": "...", "realm": "..." }
```
Return any `2xx` on success.

**Verify** — `POST {verify URL}`
```json
{ "phone": "+39...", "username": "...", "realm": "...", "otp": "123456" }
```
- `2xx` with body `{"valid": true}` (or any non-`{"valid": false}` 2xx body) → accepted
- `2xx` with `{"valid": false}` → rejected
- `4xx` → rejected

Both calls include your auth header if `otp.auth.token` / `OTP_AUTH_TOKEN` is set.

> **Security:** protect the verify endpoint (mTLS or a service token) — it is an OTP
> oracle. Rate-limit attempts on your side, since Keycloak no longer counts them.

## Flow setup

1. Admin console → **Authentication → Flows**.
2. Duplicate the **browser** flow (or **direct grant** for API-first login).
3. Remove the password step. Keep the **Username form** (or Username/Password form
   configured to resolve the user by phone via the `phoneNumber` attribute).
4. Add execution **"OTP via External HTTP Service"**, set it **REQUIRED**.
5. Configure its endpoints via the gear icon (or rely on env vars).
6. **Bindings** tab → set this flow as the **Browser flow** (or Direct grant flow).

## Creating passwordless users

Create users with the phone in the configured attribute and **no credentials**:

```http
POST /admin/realms/{realm}/users
{
  "username": "+391234567890",
  "enabled": true,
  "attributes": { "phoneNumber": ["+391234567890"] }
}
```

## Releases

Releases are fully automated by `.github/workflows/release.yml`. On every push to
`main`/`master` (or a manual *Run workflow*), the pipeline:

1. **versioning** — derives a SemVer with [GitVersion](https://gitversion.net/)
   (`GitVersion.yml`) from history and commit messages.
2. **build** — stamps the POM with that version (`mvn versions:set`), runs
   `mvn clean verify`, and uploads `keycloak-otp-http-spi-X.Y.Z.jar`.
3. **release** — creates tag `vX.Y.Z` and a GitHub Release with the JAR attached.

The version bump is driven by commit messages: include `fix`/`patch` for a patch,
`feature`/`minor` for a minor, `breaking`/`major` for a major bump (add
`+semver: skip` to a commit message to suppress a bump).

No manual tagging needed — the workflow creates the tag for you.

## License

MIT
