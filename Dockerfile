# syntax=docker/dockerfile:1

# Builds one module of the llm-gateway reactor (llm-gateway-core | llm-openrouter).
# Usage: docker build --build-arg MODULE=llm-openrouter --build-arg PORT=8085 -t llm-openrouter .
#
# NOTE: the parent POM (com.org.llm:super-pom) and learning-bom are resolved from a
# private Maven repository. Point .mvn/settings.xml (or the MAVEN_SETTINGS build
# secret) at that repository — this Dockerfile does not vendor it.

# ── Build stage ───────────────────────────────────────────────────────────────
# Java 27 images: eclipse-temurin:27 wasn't on Docker Hub yet (Sept 2026), so the build and runtime stages use
# SapMachine 27, an OpenJDK build that is also a Docker Official Image.
# There is no maven:*-27 image either, so the build stage copies Maven from the official Maven
# image; Maven itself runs on any recent JDK.
FROM sapmachine:27-jdk-ubuntu AS build
COPY --from=maven:3.9 /usr/share/maven /usr/share/maven
RUN ln -s /usr/share/maven/bin/mvn /usr/bin/mvn
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
FROM sapmachine:27-jre-ubuntu AS runtime
ARG MODULE=llm-gateway-core
ARG PORT=8080

RUN apt-get update \
    && apt-get install -y --no-install-recommends curl \
    && rm -rf /var/lib/apt/lists/* \
    && groupadd --system gateway && useradd --system --gid gateway --no-create-home gateway

WORKDIR /app
COPY --from=build /build/app.jar app.jar
# llm-gateway-core's logback config writes logs/llm-gateway.json under /app, which the gateway user must be able to create
RUN mkdir -p logs && chown gateway:gateway app.jar logs

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
