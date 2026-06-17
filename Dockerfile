# Example: bake the SPI into a custom Keycloak image.
# Build the JAR first:  mvn clean verify
# Then:                 docker build -t my-keycloak:otp .

FROM quay.io/keycloak/keycloak:26.6.3 AS builder

COPY target/keycloak-otp-http-spi.jar /opt/keycloak/providers/
RUN /opt/keycloak/bin/kc.sh build

FROM quay.io/keycloak/keycloak:26.6.3
COPY --from=builder /opt/keycloak /opt/keycloak

# Endpoints can be supplied here or set per-flow in the admin UI.
# ENV OTP_REQUEST_URL=https://ms-calls.internal/otp/request
# ENV OTP_VERIFY_URL=https://ms-calls.internal/otp/verify
# ENV OTP_AUTH_TOKEN="Bearer change-me"

ENTRYPOINT ["/opt/keycloak/bin/kc.sh"]
CMD ["start", "--optimized"]
