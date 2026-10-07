# syntax=docker/dockerfile:1

# ---- Build stage: compile and produce the executable jar ----
# NOTE: this project targets Java 26 / Spring Boot 4.1. Verify these image tags exist before
# building; if a JRE image isn't published for 26 yet, use eclipse-temurin:26-jdk for runtime too.
FROM eclipse-temurin:27-jdk AS build
WORKDIR /workspace

# Warm the Gradle dependency cache first so source-only changes don't re-download everything.
COPY gradlew settings.gradle build.gradle ./
COPY gradle ./gradle
RUN --mount=type=cache,target=/root/.gradle \
    ./gradlew --no-daemon dependencies || true

COPY src ./src
RUN --mount=type=cache,target=/root/.gradle \
    ./gradlew --no-daemon clean bootJar \
    && cp build/libs/*.jar app.jar

# ---- Runtime stage: minimal JRE, non-root ----
FROM eclipse-temurin:27-jre AS runtime
WORKDIR /app

# Run as an unprivileged user.
RUN groupadd --system app && useradd --system --gid app --home /app app

# Trust the RDS global CA bundle so the JDBC URL's sslMode=VERIFY_IDENTITY can validate the
# database server certificate. The bundle contains multiple certs, so split it and import each
# (a single keytool import would only take the first). Done as root, before dropping privileges.
ADD https://truststore.pki.rds.amazonaws.com/global/global-bundle.pem /tmp/rds-ca.pem
RUN csplit -z -f /tmp/rds-cert- /tmp/rds-ca.pem '/-----BEGIN CERTIFICATE-----/' '{*}' \
 && for c in /tmp/rds-cert-*; do \
      keytool -importcert -noprompt -alias "rds-$(basename "$c")" \
        -file "$c" -cacerts -storepass changeit; \
    done \
 && rm -f /tmp/rds-ca.pem /tmp/rds-cert-*

COPY --from=build /workspace/app.jar app.jar

USER app
EXPOSE 8080

# JVM is container-aware by default; add heap/GC flags or agents here as needed.
ENV JAVA_OPTS=""

ENTRYPOINT ["sh", "-c", "exec java $JAVA_OPTS -jar app.jar"]
