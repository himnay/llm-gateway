# syntax=docker/dockerfile:1

# Builds one module of the llm-gateway reactor (llm-gateway-core | llm-openrouter).
# Usage: docker build --build-arg MODULE=llm-openrouter --build-arg PORT=8085 -t llm-openrouter .
#
# NOTE: the parent POM (com.org.llm:super-pom) and learning-bom are resolved from a
# private Maven repository. Point .mvn/settings.xml (or the MAVEN_SETTINGS build
# secret) at that repository — this Dockerfile does not vendor it.

# ── Build stage ───────────────────────────────────────────────────────────────
FROM maven:3.9-eclipse-temurin-25 AS build
ARG MODULE=llm-gateway-core
WORKDIR /build

COPY pom.xml .
COPY llm-gateway-core/pom.xml llm-gateway-core/pom.xml
COPY llm-openrouter/pom.xml llm-openrouter/pom.xml
RUN --mount=type=secret,id=maven_settings,target=/root/.m2/settings.xml \
    mvn -B dependency:go-offline -pl ${MODULE} -am -DskipTests

COPY llm-gateway-core/src llm-gateway-core/src
COPY llm-openrouter/src llm-openrouter/src
RUN --mount=type=secret,id=maven_settings,target=/root/.m2/settings.xml \
    mvn -B clean package -pl ${MODULE} -am -DskipTests -Djacoco.skip=true -Dspotless.check.skip=true \
    && cp ${MODULE}/target/${MODULE}-*.jar /build/app.jar

# ── Runtime stage ─────────────────────────────────────────────────────────────
FROM eclipse-temurin:25-jre AS runtime
ARG MODULE=llm-gateway-core
ARG PORT=8080

RUN apt-get update \
    && apt-get install -y --no-install-recommends curl \
    && rm -rf /var/lib/apt/lists/* \
    && groupadd --system gateway && useradd --system --gid gateway --no-create-home gateway

WORKDIR /app
COPY --from=build /build/app.jar app.jar
RUN chown gateway:gateway app.jar

USER gateway
EXPOSE ${PORT}
ENV SERVER_PORT=${PORT}

# llm-gateway-core serves under /llm/v1, llm-openrouter under /openrouter/v1 — both expose the
# same actuator readiness group, just override HEALTHCHECK_PATH at build/run time if it differs.
ARG HEALTHCHECK_PATH=/llm/v1/actuator/health/readiness
ENV HEALTHCHECK_PATH=${HEALTHCHECK_PATH}
HEALTHCHECK --interval=30s --timeout=5s --start-period=40s --retries=3 \
    CMD curl -f http://localhost:${PORT}${HEALTHCHECK_PATH} || exit 1

ENTRYPOINT ["java", "-XX:+UseContainerSupport", "-jar", "app.jar"]
