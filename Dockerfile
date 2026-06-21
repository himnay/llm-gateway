# syntax=docker/dockerfile:1

# ── Build stage ───────────────────────────────────────────────────────────────
# NOTE: the parent POM (com.org.llm:super-pom) and learning-bom are resolved from a
# private Maven repository. Point .mvn/settings.xml (or the MAVEN_SETTINGS build
# secret) at that repository — this Dockerfile does not vendor it.
FROM maven:3.9-eclipse-temurin-25 AS build
WORKDIR /build

COPY pom.xml .
RUN --mount=type=secret,id=maven_settings,target=/root/.m2/settings.xml \
    mvn -B dependency:go-offline -DskipTests

COPY src ./src
RUN --mount=type=secret,id=maven_settings,target=/root/.m2/settings.xml \
    mvn -B clean package -DskipTests -Djacoco.skip=true \
    && cp target/llm-gateway-*.jar /build/app.jar

# ── Runtime stage ─────────────────────────────────────────────────────────────
FROM eclipse-temurin:25-jre AS runtime

RUN apt-get update \
    && apt-get install -y --no-install-recommends curl \
    && rm -rf /var/lib/apt/lists/* \
    && groupadd --system gateway && useradd --system --gid gateway --no-create-home gateway

WORKDIR /app
COPY --from=build /build/app.jar app.jar
RUN chown gateway:gateway app.jar

USER gateway
EXPOSE 8080

HEALTHCHECK --interval=30s --timeout=5s --start-period=40s --retries=3 \
    CMD curl -f http://localhost:8080/llm/v1/actuator/health/readiness || exit 1

ENTRYPOINT ["java", "-XX:+UseContainerSupport", "-jar", "app.jar"]
