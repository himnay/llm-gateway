# LLM Gateway

A production-ready, reactive Spring Boot gateway that provides a single unified API for multiple LLM providers (OpenAI, HuggingFace, Cohere, Anthropic Claude, Ollama). Handles routing, failover, multi-turn chat memory, prompt safety, guardrails (in-process + a LangChain-based guardrails sidecar), caching, Keycloak/OAuth2 authentication, and full observability out of the box.

---

## What's New (v2.3)

Authentication moved from a custom X-API-Key/Postgres mechanism to Keycloak-issued OAuth2 JWTs:

| # | Category      | Change                                                                                                                                                                                                                                                                                                                                           |
|---|---------------|--------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------|
| 1 | **Breaking**  | `X-API-Key` header auth is **removed**. Every protected request now needs `Authorization: Bearer <jwt>`, where the JWT is issued by Keycloak.                                                                                                                                                                                                    |
| 2 | **Security**  | `SecurityConfig` now configures `oauth2ResourceServer().jwt(...)` with a custom `JwtAuthenticationConverter` that maps Keycloak's `realm_access.roles` claim to `ROLE_*` authorities (Keycloak doesn't use the standard `scope` claim Spring Security reads by default).                                                                         |
| 3 | **Removed**   | `ApiKeyService`, `AdminHandler`, and the `/admin/keys` CRUD endpoints are gone — there's no key registry to administer anymore; identity and lifecycle live in Keycloak.                                                                                                                                                                         |
| 4 | **Database**  | Added `V4__drop_api_keys.sql` — drops the now-unused `api_keys` table (existing `V1`/`V2` migrations are left untouched per Flyway convention; history isn't rewritten).                                                                                                                                                                         |
| 5 | **Infra**     | Added a `keycloak` service to `docker-compose.yml` (`quay.io/keycloak/keycloak`, dev mode, realm auto-imported from `docker/keycloak/llm-gateway-realm.json`) so OAuth2 works out of the box locally.                                                                                                                                            |
| 6 | **Config**    | `spring.security.oauth2.resourceserver.jwt.issuer-uri` added, defaulting to the local Keycloak realm; override with `KEYCLOAK_ISSUER_URI` for any other deployment.                                                                                                                                                                              |
| 7 | **Known gap** | `RequestLogService` audit rows still don't capture caller identity — `LlmGatewayFacade` has always passed `client_id = null` to the audit log (pre-existing, not introduced here). Wiring the JWT subject/`preferred_username` claim into the audit log is a natural follow-up now that every caller is authenticated, but is out of scope here. |

---

## What's New (v2.2)

A Spring AI 2.0 alignment review plus a best-practices pass:

| #  | Category                 | Change                                                                                                                                                                                                                                                                                                                  |
|----|--------------------------|-------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------|
| 1  | **Spring AI 2.0 review** | Audited every custom abstraction against Spring AI 2.0 out-of-box features (ChatClient/Advisors, `MessageChatMemoryAdvisor`, `@Tool`, `EmbeddingModel`, `ImageModel`, `PromptTemplate`, `.entity()` structured output). Verdict: the codebase already uses these idiomatically — there was very little left to replace. |
| 2  | **Cleanup**              | Removed `PromptGuardAdvisor` — it was a pure pass-through that only logged prompt length; injection sanitization already happens upstream in `LlmGatewayFacade`. The advisor chain is now 8 steps, not 9.                                                                                                               |
| 3  | **API versioning**       | Base path changed from `/llm` to `/llm/v1` (`spring.webflux.base-path`) so a future breaking v2 can coexist.                                                                                                                                                                                                            |
| 4  | **Security**             | `gateway.cors.allowed-origins` was defined in config but never wired to anything — added a `CorsConfigurationSource` bean so it actually takes effect.                                                                                                                                                                  |
| 5  | **Docs**                 | Functional routes (`LlmRouterConfig`) are now annotated with springdoc `@RouterOperation`/`@Operation`, so Swagger UI shows real summaries/descriptions/schemas instead of a bare skeleton.                                                                                                                             |
| 6  | **Observability**        | Added k8s-style actuator health groups — `/actuator/health/readiness` (Redis + R2DBC + DB) and `/actuator/health/liveness`.                                                                                                                                                                                             |
| 7  | **Containerization**     | Added a multi-stage `Dockerfile` (non-root user, `HEALTHCHECK`) and `.dockerignore` for the main app — previously only the guardrails sidecar had one.                                                                                                                                                                  |
| 8  | **Security tooling**     | Added OWASP `dependency-check-maven`, opt-in via `mvn -P security-scan verify` (kept out of the default build — needs network/NVD access).                                                                                                                                                                              |
| 9  | **Code quality**         | Added Spotless + Google Java Format, checked on every `mvn verify`.                                                                                                                                                                                                                                                     |
| 10 | **CI**                   | `.github/workflows/ci.yml` now also runs `spotless:check`, a non-blocking OWASP scan job, and a non-blocking Docker build validation job.                                                                                                                                                                               |

---

## What's New (v2.1)

Security, correctness, and feature improvements:

| #  | Category        | Change                                                                                                                                       |
|----|-----------------|----------------------------------------------------------------------------------------------------------------------------------------------|
| 1  | **Security**    | Auth is now **enabled by default** — set `GATEWAY_AUTH_ENABLED=false` only for local dev                                                     |
| 2  | **Security**    | Redis password enforced — `REDIS_PASSWORD` defaults to `gatewayredis` in both app and Docker Compose                                         |
| 3  | **Security**    | X-Forwarded-For only trusted from configured `GATEWAY_TRUSTED_PROXIES` (prevents IP spoofing)                                                |
| 4  | **Security**    | Dev seed keys in `V2__seed_api_keys.sql` carry a hard ROTATE warning                                                                         |
| 5  | **Bug fix**     | `GlobalExceptionHandler` was a `@RestControllerAdvice` that never fired for functional routes — replaced with a proper `WebExceptionHandler` |
| 6  | **Bug fix**     | `RedisChatMemoryRepository.findConversationIds()` used blocking `KEYS *` — replaced with non-blocking `SCAN` cursor                          |
| 7  | **Bug fix**     | `LlmResponse.response` (duplicate of `content`, never populated) removed                                                                     |
| 8  | **Bug fix**     | `CompletableFuture.supplyAsync` in auto-failover used ForkJoinPool — replaced with `Mono.fromCallable().subscribeOn(boundedElastic)`         |
| 9  | **Reliability** | `Hooks.enableAutomaticContextPropagation()` called at startup — MDC values (traceId, requestId) now survive Reactor scheduler hops           |
| 10 | **Reliability** | Streaming handler now runs the inbound guardrail chain, enforces a per-stream timeout, and returns structured error events on failure        |
| 11 | **Performance** | `LlmMetricsService` pre-registers counters/timers at startup instead of rebuilding on every request                                          |
| 12 | **Feature**     | `POST /llm/v1/embed` — vector embedding endpoint (OpenAI `text-embedding-3-small` by default)                                                |
| 13 | **Feature**     | Admin API key management — `GET/POST /llm/v1/admin/keys`, `PATCH/DELETE /llm/v1/admin/keys/{id}`                                             |
| 14 | **Feature**     | Audit log — every request persisted to `request_log` table (prompt hash, provider, tokens, latency; raw prompt never stored)                 |
| 15 | **Feature**     | `GET /llm/v1/models` now returns the **configured** default model per provider, not a hardcoded list                                         |
| 16 | **Docs**        | Swagger UI available at `/llm/v1/swagger-ui.html` (via `springdoc-openapi-starter-webflux-ui`)                                               |
| 17 | **Config**      | `GATEWAY_TRUSTED_PROXIES` and `LLM_STREAM_TIMEOUT_SECONDS` added                                                                             |

---

## Runtime Migration: Java 21 → 25, Spring AI → 2.0.0

This service now targets **Java 25** and **Spring AI 2.0.0** (up from Java 21 and Spring AI 2.0.0-M8), inherited from the shared `super-pom` / `llm-bom` chain — no module-level `java.version`, `maven.compiler.release`, or `spring-ai.version` override exists in this repo's `pom.xml`, so the bump required no POM edits here. The CI workflow (`.github/workflows/ci.yml`) was updated to provision JDK 25 via `actions/setup-java@v4` (it previously pinned JDK 21).

**Verified in this environment** (JDK 25, Docker available):
- `mvn -o compile` — succeeds under Azul Zulu 25.0.3.
- `mvn clean test` with a real Docker daemon — **33 tests run / 0 skipped**. 22 pass cleanly. The 11 `LlmGatewayIntegrationTest` cases (now actually exercising real Testcontainers-backed Postgres 18 and Redis containers instead of being skipped) fail, but root-caused to the same pre-existing gap described below (`RemoteGuardrailClient` needs a `Tracer` bean that nothing in the codebase provides) — not a Docker- or migration-related regression. Fixed one genuine, Docker-surfaced bug along the way: Spring Boot's R2DBC Testcontainers service-connection factory (`PostgresR2dbcDatabaseContainerConnectionDetailsFactory`) needs `org.testcontainers:r2dbc` on the classpath, which was missing from `pom.xml` — only the JDBC-flavored `org.testcontainers:postgresql` module was declared. Added the `org.testcontainers:r2dbc` test dependency; this alone fixed the `NoClassDefFoundError: org/testcontainers/r2dbc/R2DBCDatabaseContainer` failure that previously prevented the R2DBC connection factory from initializing against the real container.
- `docker compose up -d postgres redis` + `mvn spring-boot:run` — real boot attempted against live Postgres/Redis containers. Boot proceeds well past R2DBC/Redis repository scanning and bean creation, then fails at the same `UnsatisfiedDependencyException: ... required a bean of type 'io.micrometer.tracing.Tracer' that could not be found` while wiring `RemoteGuardrailClient`, confirming this is a **pre-existing gap in in-flight (uncommitted) tracing-propagation code**, unrelated to Docker, the Java/Spring AI bump, or the R2DBC fix above — `micrometer-tracing-bridge-otel` resolves correctly on the classpath, but no `Tracer` bean is produced anywhere in the codebase. Not fixed here since it's part of someone else's in-progress tracing work, outside this verification's scope.
- **Heads up if you re-run `LlmGatewayIntegrationTest` directly:** without the guardrails sidecar running (`docker compose up -d guardrails`), it doesn't fail fast — `RemoteGuardrailClient`'s `WebClient` call has no configured timeout, so context startup can hang rather than error. Run `mvn test -Dtest='!LlmGatewayIntegrationTest'` for a quick unit-test-only pass, or start the sidecar first.

---

## Table of Contents

- [Architecture Overview](#architecture-overview)
- [Design Patterns (GoF)](#design-patterns-gof)
- [Tech Stack](#tech-stack)
- [Features](#features)
- [Prerequisites](#prerequisites)
- [Quick Start](#quick-start)
- [Docker Compose](#docker-compose)
- [Configuration Reference](#configuration-reference)
- [Security — API Key Authentication](#security--api-key-authentication)
- [API Documentation](#api-documentation)
- [Prompt Template System](#prompt-template-system)
- [Guardrail Chain](#guardrail-chain)
- [Guardrails Service (LangServe sidecar)](#guardrails-service-langserve-sidecar)
- [Observability](#observability)
- [Project Structure](#project-structure)
- [Technology Deep Dive](#technology-deep-dive)

---

## Architecture Overview

```
Client
  │   Authorization: Bearer <jwt> (when auth enabled)
  ▼
┌─────────────────────────────────────────────────────────────────┐
│  Spring Security WebFlux — OAuth2 Resource Server                │
│  ReactiveJwtDecoder ──► Keycloak realm "llm-gateway" (JWKS)      │
│                                                                 │
│  LLM Gateway  (Spring WebFlux · Spring Cloud Gateway)           │
│                                                                 │
│  HTTP Request                                                   │
│      │                                                          │
│      ▼                                                          │
│  LlmHandler  ──► LlmGatewayFacade                              │
│  • X-Request-ID propagation                                     │
│  • Prompt validation (blank / length)                           │
│  • Per-request timeout                                          │
│                      │                                          │
│                      ├─ 1. Inbound Guardrail Chain              │
│                      │    (Chain of Responsibility)             │
│                      │    ① prompt-sanitization (injection)     │
│                      │    ② sensitive-data-redaction (PII)      │
│                      │    ③ remote-guardrails ──► Guardrails    │
│                      │    POST /guardrails/invoke   Service     │
│                      │    (LangServe sidecar, port 8000)        │
│                      ├─ 2. Redis Cache Lookup                   │
│                      ├─ 3. OTEL Observation Span                │
│                      ├─ 4. Delegate → LlmServiceProvider        │
│                      │        │                                 │
│                      │        ▼                                 │
│                      │   Spring AI ChatClient                   │
│                      │   ┌──────────────────────────────────┐  │
│                      │   │  8-Step Guardrail Chain           │  │
│                      │   │  ① ToxicityFilterAdvisor          │  │
│                      │   │  ② PiiRedactionAdvisor            │  │
│                      │   │  ③ TopicFilterAdvisor             │  │
│                      │   │  ④ MetricsAdvisor                 │  │
│                      │   │  ⑤ ChatMemoryAdvisor (Redis)      │  │
│                      │   │  ⑥ SimpleLoggerAdvisor            │  │
│                      │   │  ⑦ ResponseFormatAdvisor          │  │
│                      │   │  ⑧ HallucinationMonitorAdvisor    │  │
│                      │   └──────────────────────────────────┘  │
│                      │                                          │
│                      ├─ 5. Attach Trace/Span IDs               │
│                      └─ 6. Redis Cache Store                    │
│                                                                 │
│  Side channels:                                                 │
│    Guardrails  ──  LangServe sidecar (validate prompts/answers)  │
│    PostgreSQL  ──  API key registry (Flyway managed)            │
│    Redis       ──  Prompt cache + Chat memory                   │
│    Prometheus  ──  Metrics scraping                             │
│    OTLP        ──  Distributed traces → Grafana Tempo           │
└─────────────────────────────────────────────────────────────────┘
```

---

## Design Patterns (GoF)

The gateway is deliberately structured around Gang-of-Four patterns; each is applied where it
removes real coupling, not for its own sake:

| Pattern                       | Where                                                | Why                                                                                                                                                                        |
|-------------------------------|------------------------------------------------------|----------------------------------------------------------------------------------------------------------------------------------------------------------------------------|
| **Facade**                    | `facade/LlmGatewayFacade`                            | Single entry point hiding the pipeline (guardrails → cache → tracing → provider → metrics) from handlers                                                                   |
| **Strategy**                  | `facade/LlmServiceProvider` + 6 implementations      | Each provider is an interchangeable strategy resolved by name at runtime                                                                                                   |
| **Factory Method / Registry** | `facade/LlmProviderRegistry`                         | Discovers every `LlmServiceProvider` bean and resolves/validates providers; adding a provider needs zero gateway changes (OCP)                                             |
| **Chain of Responsibility**   | `guardrail/chain/GuardrailChain` + `GuardrailStep`s  | Ordered, pluggable inbound guardrail pipeline (sanitization → PII redaction → remote guardrails); any step can rewrite the prompt or reject the request                    |
| **Template Method**           | `service/AbstractRestLlmService`                     | Fixes the REST-call skeleton (render system prompt → POST → parse → never throw); Google/Cohere/HuggingFace implement only the hooks (endpoint, headers, payload, parsing) |
| **Adapter**                   | `guardrail/remote/RemoteGuardrailClient`             | Adapts the LangServe sidecar's REST/JSON API (`POST /guardrails/invoke`) to a typed Java interface                                                                         |
| **Observer**                  | `guardrail/event/GuardrailViolationEvent` + listener | Guardrail rejections publish events; audit logging/alerting subscribe without coupling to the chain                                                                        |
| **Decorator**                 | Spring AI advisor chain (`guardrail/*Advisor`)       | Each advisor wraps the ChatClient call, adding behaviour (toxicity, PII, metrics, memory) transparently                                                                    |
| **Builder**                   | Lombok `@Builder` on `LlmRequest`/`LlmResponse`      | Safe construction of many-field immutable-style DTOs                                                                                                                       |
| **Proxy**                     | Resilience4j `@CircuitBreaker`/`@Retry` (AOP)        | Cross-cutting resilience added via dynamic proxies, not in business code                                                                                                   |
| **Singleton**                 | Spring-managed beans                                 | All services/components are container-scoped singletons (no hand-rolled singletons)                                                                                        |

---

## Tech Stack

| Layer            | Technology                                                            |
|------------------|-----------------------------------------------------------------------|
| Runtime          | Java 25, Spring Boot 4.1.0                                            |
| Web              | Spring WebFlux (reactive, non-blocking)                               |
| Security         | Spring Security WebFlux + PostgreSQL API key table                    |
| LLM Integration  | Spring AI 2.0.0                                                       |
| LLM Providers    | OpenAI, Anthropic Claude, Ollama, Google Gemini, Cohere, HuggingFace  |
| Guardrails       | In-process chain + LangChain/FastAPI sidecar (REST, Docker)           |
| Cache + Memory   | Redis (Spring Data Redis / Lettuce)                                   |
| Database         | PostgreSQL 18 (R2DBC reactive + JDBC for Flyway)                      |
| Migrations       | Flyway                                                                |
| Resilience       | Resilience4j (Circuit Breaker, Retry, Rate Limiter)                   |
| Observability    | Micrometer + OTEL, Prometheus, Grafana Tempo                          |
| Build            | Maven 3.9+                                                            |

---

## Features

- **Multi-provider routing** — single API, routed to OpenAI, Anthropic, Ollama, Google Gemini, Cohere, or HuggingFace
- **Failover chain** — tries the next provider automatically on failure
- **Multi-turn chat** — Redis-backed conversation memory per `session_id`
- **Session management** — `DELETE /sessions/{sessionId}` clears history
- **Prompt template system** — per-provider `.st` files with variable substitution
- **Assistant message prefill** — steer response format without consuming a user turn
- **OAuth2/Keycloak authentication** — Spring Security WebFlux OAuth2 resource server validates Keycloak-issued JWTs; realm roles mapped to Spring `ROLE_*` authorities
- **8-step guardrail chain** — prompt safety, PII redaction, toxicity filter, topic restriction, hallucination monitoring, response format validation
- **External guardrails service** — LangChain-based sidecar (Docker) consulted over REST **before every LLM call** (and optionally on responses); injection/jailbreak heuristics, toxicity, PII masking, topic policy, optional LLM-as-judge; fail-open/fail-closed policy + dedicated circuit breaker
- **GoF design patterns throughout** — Facade, Strategy, Registry, Chain of Responsibility, Template Method, Adapter, Observer, Decorator, Builder, Proxy (see [Design Patterns](#design-patterns-gof))
- **Sensitive-data guard (all providers)** — provider-agnostic PII + secret redaction applied in the facade on both request and response, so nothing sensitive is sent to an LLM, cached, or logged
- **Externalised guardrail patterns** — all injection / PII / toxicity pattern lists tunable in YAML (`llm.guardrails.patterns.*`) without code changes
- **Redis prompt cache** — SHA-256 keyed (includes system prompt, template vars, assistant message), configurable TTL
- **Streaming** — SSE token streaming per provider (with guardrail chain + timeout + error events)
- **Embeddings** — `POST /llm/v1/embed` generates vector embeddings via OpenAI (or Ollama)
- **Structured output** — typed Java record extraction from LLM responses (via facade for full observability)
- **Request timeout** — configurable per-request timeout with 504 response; separate stream timeout
- **Audit log** — every request written to `request_log` table (PostgreSQL) for compliance and billing; raw prompts never stored
- **OpenAPI / Swagger UI** — live API documentation at `/llm/v1/swagger-ui.html`
- **X-Request-ID propagation** — correlation ID accepted from callers and echoed in response headers and body
- **Real health check** — `/health` probes Redis connectivity
- **Provider enable/disable** — `@ConditionalOnProperty` per service; disabled providers never start
- **Distributed tracing** — OTEL spans with trace/span IDs in every log line
- **Prometheus metrics + Grafana dashboard** — calls per provider, REST API turnaround time (`@Timed` + `http.server.requests`), latency percentiles, token usage, cache hits, rejections, errors; importable/auto-provisioned dashboard included

---

## Prerequisites

| Requirement       | Version |
|-------------------|---------|
| Java              | 25      |
| Maven             | 3.9+    |
| PostgreSQL        | 16+     |
| Redis             | 7+      |
| Ollama (optional) | latest  |

---

## Quick Start

### 1. Clone and set environment variables

```bash
git clone https://github.com/your-org/llm-gateway.git
cd llm-gateway

export OPENAI_API_KEY=sk-...
export ANTHROPIC_API_KEY=sk-ant-...
```

### 2. Start dependencies with Docker Compose

```bash
docker compose up -d postgres redis guardrails keycloak
```

The `guardrails` service is built locally from `guardrails-service/` (base image:
`langchain/langchain`) the first time you run this. `keycloak` auto-imports the
`llm-gateway` realm from `docker/keycloak/llm-gateway-realm.json` on first boot — give it
~30s to come up, then check `http://localhost:8081` (admin console login: `admin`/`admin`).

> **Spring Boot 4 + Flyway gotcha.** In Spring Boot 4 the per-technology
> auto-configurations were split out of `spring-boot-autoconfigure` into dedicated
> modules, so `flyway-core` alone no longer triggers `FlywayAutoConfiguration` — the
> `org.springframework.boot:spring-boot-flyway` module must be on the classpath (it is,
> in `pom.xml`). Additionally, because an R2DBC `ConnectionFactory` bean is present,
> `DataSourceAutoConfiguration` backs off and no shared JDBC `DataSource` is created;
> Flyway therefore runs over its own dedicated datasource built from the
> `spring.flyway.url` / `user` / `password` properties. If you ever see an empty
> `spring_ai` database with no tables, this configuration is what makes migrations run.

### 3. Build and run

```bash
mvn spring-boot:run
```

### 4. Smoke test

Auth is **enabled by default**, so you need a Keycloak access token first (see
[Security — Keycloak / OAuth2 Authentication](#security--keycloak--oauth2-authentication)
for the full explanation):

```bash
TOKEN=$(curl -s -X POST \
  http://localhost:8081/realms/llm-gateway/protocol/openid-connect/token \
  -d 'grant_type=client_credentials' \
  -d 'client_id=llm-gateway-client' \
  -d 'client_secret=llm-gateway-dev-secret' \
  | jq -r .access_token)

curl -X POST http://localhost:8080/llm/v1/query \
  -H "Content-Type: application/json" \
  -H "Authorization: Bearer $TOKEN" \
  -d '{"prompt": "Hello!", "provider": "OPENAI"}'

# Or disable auth entirely for local dev:
# export GATEWAY_AUTH_ENABLED=false
```

---

## Docker Compose

`docker-compose.yml` includes all infrastructure services:

```bash
# Start everything
docker compose up -d

# Start only infrastructure (run the gateway from IDE/Maven)
docker compose up -d postgres redis guardrails

# Observability stack
docker compose up -d prometheus grafana tempo
```

| Service            | Port  | Purpose                                                                                      |
|--------------------|-------|----------------------------------------------------------------------------------------------|
| Guardrails sidecar | 8000  | LangServe guardrails API (`POST /guardrails/invoke`, playground at `/guardrails/playground`) |
| PostgreSQL         | 5432  | API key registry                                                                             |
| Redis              | 6379  | Prompt cache + chat memory                                                                   |
| Prometheus         | 9090  | Metrics                                                                                      |
| Grafana            | 3000  | Dashboards (admin/admin)                                                                     |
| Grafana Tempo      | 4318  | OTLP trace collector                                                                         |

---

## Configuration Reference

All values can be overridden via environment variables.

### Server

| Property                   | Env Var | Default   |
|----------------------------|---------|-----------|
| `server.port`              | —       | `8080`    |
| `spring.webflux.base-path` | —       | `/llm/v1` |

### PostgreSQL

| Property | Env Var             | Default      |
|----------|---------------------|--------------|
| Host     | `POSTGRES_HOST`     | `localhost`  |
| Port     | `POSTGRES_PORT`     | `5432`       |
| Database | `POSTGRES_DB`       | `spring_ai`  |
| Username | `POSTGRES_USER`     | `postgres`   |
| Password | `POSTGRES_PASSWORD` | `postgres`   |

### Redis

| Property | Env Var          | Default     |
|----------|------------------|-------------|
| Host     | `REDIS_HOST`     | `localhost` |
| Port     | `REDIS_PORT`     | `6379`      |
| Password | `REDIS_PASSWORD` | _(empty)_   |

### LLM Providers

| Provider        | Env Var               | Default                  |
|-----------------|-----------------------|--------------------------|
| OpenAI          | `OPENAI_API_KEY`      | `sk-placeholder`         |
| Anthropic       | `ANTHROPIC_API_KEY`   | `placeholder`            |
| Ollama base URL | `OLLAMA_URL`          | `http://localhost:11434` |
| Google          | `GOOGLE_API_KEY`      | —                        |
| HuggingFace     | `HUGGINGFACE_API_KEY` | —                        |
| Cohere          | `COHERE_API_KEY`      | —                        |

Disable a provider entirely:
```bash
# Removes the service bean — provider never starts or connects
LLM_PROVIDERS_OPENAI_ENABLED=false
```

### Cache & Memory

| Env Var                     | Default | Description              |
|-----------------------------|---------|--------------------------|
| `LLM_CACHE_ENABLED`         | `true`  | Toggle prompt cache      |
| `LLM_CACHE_TTL_MINUTES`     | `60`    | Cache entry lifetime     |
| `LLM_CHAT_MEMORY_TTL_HOURS` | `24`    | Session history lifetime |

### Request

| Env Var                       | Default | Description                           |
|-------------------------------|---------|---------------------------------------|
| `LLM_REQUEST_TIMEOUT_SECONDS` | `30`    | Per-request timeout (→ 504 on breach) |

### Rate Limiting

| Env Var                       | Default |
|-------------------------------|---------|
| `RATE_LIMITER_ENABLED`        | `true`  |
| `RATE_LIMITER_MAX_REQUESTS`   | `60`    |
| `RATE_LIMITER_WINDOW_SECONDS` | `60`    |

### Guardrails

| Env Var                       | Default   |
|-------------------------------|-----------|
| `LLM_PII_REDACTION_ENABLED`   | `true`    |
| `LLM_TOXICITY_FILTER_ENABLED` | `true`    |
| `LLM_TOPIC_FILTER_ENABLED`    | `true`    |
| `LLM_BLOCKED_TOPICS`          | _(empty)_ |
| `LLM_RESPONSE_FORMAT_ENABLED` | `true`    |
| `LLM_RESPONSE_MIN_LENGTH`     | `10`      |
| `LLM_RESPONSE_MAX_LENGTH`     | `50000`   |

### External Guardrails Service (LangChain sidecar)

| Env Var                                   | Default                 | Description                                          |
|-------------------------------------------|-------------------------|------------------------------------------------------|
| `LLM_EXTERNAL_GUARDRAILS_ENABLED`         | `true`                  | Call the sidecar before every LLM call               |
| `LLM_EXTERNAL_GUARDRAILS_URL`             | `http://localhost:8000` | Sidecar base URL (`guardrails` service in compose)   |
| `LLM_EXTERNAL_GUARDRAILS_TIMEOUT_MS`      | `3000`                  | Per-call timeout                                     |
| `LLM_EXTERNAL_GUARDRAILS_FAIL_OPEN`       | `true`                  | Sidecar down: `true` = continue, `false` = reject    |
| `LLM_EXTERNAL_GUARDRAILS_VALIDATE_OUTPUT` | `false`                 | Also validate LLM responses before returning them    |

Sidecar-side knobs (set on the `guardrails` container in `docker-compose.yml`):

| Env Var                     | Default    | Description                                              |
|-----------------------------|------------|----------------------------------------------------------|
| `GUARDRAILS_BLOCKED_TOPICS` | _(empty)_  | Comma-separated restricted topics                        |
| `GUARDRAILS_MAX_LENGTH`     | `10000`    | Max accepted text length                                 |
| `GUARDRAILS_LLM_CHECK`      | `false`    | Enable LangChain LLM-as-judge (needs `OPENAI_API_KEY`)   |

### Security

| Env Var                                | Default                                       | Description                                           |
|----------------------------------------|-----------------------------------------------|-------------------------------------------------------|
| `GATEWAY_AUTH_ENABLED`                 | `true`                                        | API-key auth — set `false` only for local dev         |
| `GATEWAY_CORS_ORIGINS`                 | `http://localhost:3000,http://localhost:8080` | Comma-separated allowed CORS origins                  |
| `LLM_SANITIZATION_ENABLED`             | `true`                                        | Prompt injection detection                            |
| `LLM_MAX_PROMPT_LENGTH`                | `10000`                                       | Hard cap on prompt characters                         |
| `LLM_SENSITIVE_DATA_ENABLED`           | `true`                                        | PII + secret redaction (all providers)                |
| `LLM_SENSITIVE_DATA_REDACT_PROMPT`     | `true`                                        | Redact before sending to provider                     |
| `LLM_SENSITIVE_DATA_REDACT_RESPONSE`   | `true`                                        | Redact before returning to caller                     |

### Observability

| Env Var                       | Default                 |
|-------------------------------|-------------------------|
| `OTEL_EXPORTER_OTLP_ENDPOINT` | `http://localhost:4318` |

---

## Security — Keycloak / OAuth2 Authentication

### How it works

1. Client obtains a JWT access token from Keycloak (client-credentials grant for
   service/script callers, or password/authorization-code grant for human users)
2. Client sends `Authorization: Bearer <jwt>` on every request
3. Spring Security's OAuth2 resource server validates the JWT signature against
   Keycloak's JWKS endpoint (derived from `issuer-uri`) and checks `exp`/`iat`/`iss`
4. `SecurityConfig`'s custom `JwtAuthenticationConverter` reads the `realm_access.roles`
   claim and maps each role to a `ROLE_<NAME>` Spring authority
5. If the token is missing, expired, or fails signature validation → **HTTP 401**
   (returned automatically by `BearerTokenAuthenticationEntryPoint`)
6. Actuator endpoints (`/actuator/**`) and the public info/docs routes are always
   permitted without a token

There is no local user/key registry anymore — Keycloak is the single source of identity.
Disable auth entirely for local dev with `GATEWAY_AUTH_ENABLED=false`.

### Local Keycloak setup

`docker compose up -d keycloak` starts Keycloak in dev mode and auto-imports the
`llm-gateway` realm from `docker/keycloak/llm-gateway-realm.json`:

| Resource                    | Value                                                                                                     |
|-----------------------------|-----------------------------------------------------------------------------------------------------------|
| Admin console               | `http://localhost:8081` (`admin` / `admin`, override via `KEYCLOAK_ADMIN_USER`/`KEYCLOAK_ADMIN_PASSWORD`) |
| Realm                       | `llm-gateway`                                                                                             |
| Client (machine-to-machine) | `llm-gateway-client` / secret `llm-gateway-dev-secret` — service account + direct-access-grants enabled   |
| Demo human user             | `dev-user` / `devpassword` (realm role `gateway-user`)                                                    |
| Realm roles                 | `gateway-user`, `gateway-admin` (the client's service account holds `gateway-admin`)                      |

> ⚠️ The client secret and demo user password are committed to source control for
> **local development only**. Rotate them (or re-import a different realm) before any
> real deployment, and never reuse `llm-gateway-dev-secret` outside your laptop.

### Getting a token

**Service-to-service (client-credentials grant)** — what curl/scripts/CI should use:

```bash
curl -s -X POST http://localhost:8081/realms/llm-gateway/protocol/openid-connect/token \
  -d 'grant_type=client_credentials' \
  -d 'client_id=llm-gateway-client' \
  -d 'client_secret=llm-gateway-dev-secret' \
  | jq -r .access_token
```

**Human user (password grant, dev/test only)**:

```bash
curl -s -X POST http://localhost:8081/realms/llm-gateway/protocol/openid-connect/token \
  -d 'grant_type=password' \
  -d 'client_id=llm-gateway-client' \
  -d 'client_secret=llm-gateway-dev-secret' \
  -d 'username=dev-user' \
  -d 'password=devpassword' \
  | jq -r .access_token
```

Use the returned `access_token` as `Authorization: Bearer <token>` on every gateway call.
Tokens expire after 15 minutes (`accessTokenLifespan` in the realm export) — re-request
when you get a 401.

### Adding roles or callers

Everything is managed in Keycloak, not in this codebase:

- New machine client → create another confidential client with `serviceAccountsEnabled`,
  assign it whichever realm role you need, in the Keycloak admin console (or extend
  `docker/keycloak/llm-gateway-realm.json` and re-run `docker compose up -d keycloak`
  — note `--import-realm` only imports on first boot of a fresh Keycloak volume).
- New human user → create a user in the realm and assign realm roles.
- Pointing at a different/production Keycloak → set `KEYCLOAK_ISSUER_URI` to that
  realm's issuer URL; no code changes needed.

### Authorizing by role (extension point)

The gateway currently treats "has a valid token" as sufficient for every protected
route — it doesn't yet gate specific endpoints by role (mirroring the old API-key
model, where any valid key could call anything). To restrict an endpoint to
`gateway-admin`, add to `SecurityConfig`:

```java
.pathMatchers("/llm/v1/some-admin-route").hasRole("GATEWAY-ADMIN")
```

---

## API Documentation

Base URL: `http://localhost:8080/llm/v1`

All request bodies are JSON. All responses are JSON.

**Common request headers:**

| Header                           | Description                                                                                            |
|----------------------------------|--------------------------------------------------------------------------------------------------------|
| `Content-Type: application/json` | Required                                                                                               |
| `Authorization: Bearer <jwt>`    | Required when `GATEWAY_AUTH_ENABLED=true` — see [Security](#security--keycloak--oauth2-authentication) |
| `X-Request-ID: <uuid>`           | Optional; echoed back as `X-Request-ID` response header and `correlation_id` in body                   |

---

### Request Body — `LlmRequest`

```jsonc
{
  "prompt": "Explain recursion",           // required, max 10,000 chars
  "provider": "OPENAI",                    // OPENAI | ANTHROPIC | OLLAMA
  "model": "gpt-4o",                       // optional, overrides default
  "system_prompt": "You are an expert",    // raw system prompt (no template_vars)
  "max_tokens": 2048,
  "temperature": 0.7,
  "session_id": "user-123-abc",            // required for /chat
  "providers": ["OPENAI", "ANTHROPIC"],    // for /failover
  "template_vars": {                       // activates .st template rendering
    "role": "a senior software engineer",
    "context": "The user is learning Java."
  },
  "assistant_message": "Sure! Here is"    // optional assistant prefill
}
```

---

### GET `/health`

Checks gateway liveness and Redis connectivity.

```bash
curl http://localhost:8080/llm/v1/health
```

```json
{ "status": "UP", "service": "LLM Gateway", "version": "2.0.0-spring-ai", "redis": "UP" }
```

Status is `DEGRADED` when Redis is unreachable.

---

### GET `/providers`

```bash
curl http://localhost:8080/llm/v1/providers
```
```json
{ "count": 3, "providers": ["openai", "anthropic", "ollama"] }
```

---

### GET `/models`

Returns available models per provider.

---

### POST `/query`

Single-turn query.

```bash
curl -X POST http://localhost:8080/llm/v1/query \
  -H "Content-Type: application/json" \
  -H "X-Request-ID: my-trace-123" \
  -d '{"prompt": "What is the capital of France?", "provider": "OPENAI"}'
```

```json
{
  "provider": "OpenAI", "model": "gpt-4o",
  "content": "The capital of France is Paris.",
  "prompt_tokens": 15, "completion_tokens": 9, "total_tokens": 24,
  "latency_ms": 820, "cache_hit": false,
  "request_id": "my-trace-123", "correlation_id": "my-trace-123",
  "trace_id": "abc123...", "timestamp": 1748725200000
}
```

---

### POST `/failover`

Tries providers sequentially until one succeeds.

```bash
curl -X POST http://localhost:8080/llm/v1/failover \
  -H "Content-Type: application/json" \
  -d '{"prompt": "Hello", "providers": ["OPENAI", "ANTHROPIC", "OLLAMA"]}'
```

Default chain when `providers` is omitted: `openai → anthropic → ollama`.

---

### POST `/chat`

Multi-turn conversation. `session_id` is mandatory.

```bash
# Turn 1
curl -X POST http://localhost:8080/llm/v1/chat \
  -H "Content-Type: application/json" \
  -d '{"prompt": "My name is Alice.", "provider": "OPENAI", "session_id": "alice-001"}'

# Turn 2 — model remembers "Alice"
curl -X POST http://localhost:8080/llm/v1/chat \
  -H "Content-Type: application/json" \
  -d '{"prompt": "What is my name?", "provider": "OPENAI", "session_id": "alice-001"}'
```

---

### DELETE `/sessions/{sessionId}`

Clears all conversation history for a session from Redis.

```bash
curl -X DELETE http://localhost:8080/llm/v1/sessions/alice-001
# → 204 No Content
```

---

### POST `/{provider}/chat`

Per-provider chat via path variable.

```bash
curl -X POST http://localhost:8080/llm/v1/anthropic/chat \
  -H "Content-Type: application/json" \
  -d '{"prompt": "Explain the CAP theorem.", "session_id": "dev-42"}'
```

---

### POST `/{provider}/stream`

Server-Sent Events streaming (`text/event-stream`).

```bash
curl -X POST http://localhost:8080/llm/v1/openai/stream \
  -H "Content-Type: application/json" \
  -H "Accept: text/event-stream" \
  -d '{"prompt": "Write a haiku.", "session_id": "stream-1"}'
```

---

### POST `/openai/extract`

Structured output extraction — response is deserialised into a typed Java record. Runs through the facade for full tracing and circuit-breaker coverage.

```bash
curl -X POST http://localhost:8080/llm/v1/openai/extract \
  -H "Content-Type: application/json" \
  -d '{"prompt": "Extract: meeting on 2026-06-15 at 14:00 in Room 3."}'
```

---

### POST `/embed`

Generate a vector embedding for the given text (OpenAI by default).

```bash
curl -X POST http://localhost:8080/llm/v1/embed \
  -H "Content-Type: application/json" \
  -H "Authorization: Bearer $TOKEN" \
  -d '{"text": "The quick brown fox jumps over the lazy dog."}'
```

Response includes `embedding` (float array), `dimensions`, `model`, and `provider`.

---

## Prompt Template System

System prompts live in `.st` files under `src/main/resources/prompts/`. Each provider has its own template.

| File                   | Provider                   |
|------------------------|----------------------------|
| `system-openai.st`     | OpenAI                     |
| `system-anthropic.st`  | Anthropic Claude           |
| `system-ollama.st`     | Ollama                     |
| `system-default.st`    | Fallback                   |
| `assistant-starter.st` | Assistant prefill template |

### Template variables

| Variable     | Default                  | Description          |
|--------------|--------------------------|----------------------|
| `{role}`     | `a helpful AI assistant` | Persona              |
| `{context}`  | _(empty)_                | Background context   |
| `{date}`     | Today                    | Current date         |
| `{language}` | `English`                | Response language    |
| `{starter}`  | `I understand...`        | Prefill opening text |

### Resolution priority

1. `template_vars` present → render per-provider `.st` with merged vars
2. `system_prompt` set (no `template_vars`) → use verbatim (backward compatible)
3. Neither → render `.st` with defaults only

---

## Guardrail Chain

Guardrails run at **two levels**:

### Level 1 — Gateway guardrail chain (every provider)

A GoF *Chain of Responsibility* (`guardrail/chain/GuardrailChain`) executed by the facade
**before any provider is called** — including the REST providers that bypass the Spring AI
advisor chain. Steps run in `Ordered` order; each can rewrite the prompt or reject the
request with HTTP 400 (which also publishes a `GuardrailViolationEvent` for the audit log):

| Order | Step                       | What it does                                                                           |
|-------|----------------------------|----------------------------------------------------------------------------------------|
| 100   | `prompt-sanitization`      | Injection/jailbreak regex blocking, strip + whitespace normalising                     |
| 200   | `sensitive-data-redaction` | PII/secret masking (`[EMAIL]`, `[API_KEY]`, …)                                         |
| 300   | `remote-guardrails`        | REST call to the [LangServe guardrails sidecar](#guardrails-service-langserve-sidecar) |

Adding a step = one `@Component` implementing `GuardrailStep` — no facade changes.

### Level 2 — Spring AI advisor chain (ChatClient providers)

```
INPUT  ──► ① ToxicityFilterAdvisor     Blocks harmful content → HTTP 400
           ② PiiRedactionAdvisor       Masks PII before sending; async scan of output
           ③ TopicFilterAdvisor        Blocks restricted topics → HTTP 400
           ④ MetricsAdvisor            Records prompt length and per-provider metrics
           ⑤ ChatMemoryAdvisor         Injects Redis conversation history
           ⑥ SimpleLoggerAdvisor       Logs full prompt/response (debug)
      ──► LLM Provider ◄──
           ⑦ ResponseFormatAdvisor     Validates length, refusals, truncation (async)
           ⑧ HallucinationMonitorAdvisor  Heuristic uncertainty scoring (async)
OUTPUT ◄──
```

> Prompt injection detection/sanitization runs upstream of this chain (Level 1, step
> `prompt-sanitization`, in `LlmGatewayFacade`) — there's no separate logging-only advisor
> here anymore; it added an extra hop with no behaviour of its own.

> **Production note:** PII and toxicity detection use regex/keyword lists. For higher accuracy, integrate a dedicated service such as AWS Comprehend, Azure AI Content Safety, or Microsoft Presidio. The hallucination monitor is a heuristic signal — consider RAG for factual grounding.

### Sensitive-data guard (all providers)

The guardrail advisor chain above only runs for the Spring AI **ChatClient** providers
(OpenAI, Anthropic, Ollama). To guarantee that **no PII or secret is ever sent to an
LLM, cached, or logged — for every provider** (including the custom REST providers
Google, Cohere, HuggingFace that bypass the advisor chain) — the `LlmGatewayFacade`
applies a provider-agnostic `SensitiveDataRedactor`:

- **Inbound** — the prompt is redacted *before* it is forwarded to any provider or
  written to the cache. Detected spans become typed placeholders (`[EMAIL]`,
  `[API_KEY]`, `[CREDIT_CARD]`, …).
- **Outbound** — the model's response is scanned and redacted *before* it is cached or
  returned to the caller.
- **Logs** — the gateway only logs prompt **lengths** and redacted placeholders, never
  raw prompt/response content (Spring AI's `SimpleLoggerAdvisor` stays at INFO, which
  suppresses full-content DEBUG logging).
- **Metrics** — `llm_sensitive_data_redactions_total{provider, direction, type}`.

Detected categories: e-mail, phone, credit-card, SSN, IBAN, IP address, passport, plus
secrets — API keys (`sk-…`), AWS access keys (`AKIA…`), bearer tokens and PEM private keys.

| Env Var                               | Default  | Description                                |
|---------------------------------------|----------|--------------------------------------------|
| `LLM_SENSITIVE_DATA_ENABLED`          | `true`   | Master switch for the sensitive-data guard |
| `LLM_SENSITIVE_DATA_REDACT_PROMPT`    | `true`   | Redact before sending to the provider      |
| `LLM_SENSITIVE_DATA_REDACT_RESPONSE`  | `true`   | Redact before returning to the caller      |

### Tuning guardrail patterns without code changes

All guardrail pattern lists are externalised in `GuardrailPatternProperties`
(`llm.guardrails.patterns.*`) so they can be added/removed/edited purely in
configuration — no recompile:

| Config key                               | Used by                 | Form                |
|------------------------------------------|-------------------------|---------------------|
| `llm.guardrails.patterns.sensitive-data` | `SensitiveDataRedactor` | `name → regex` map  |
| `llm.guardrails.patterns.injection`      | `PromptSanitizer`       | list of regex       |
| `llm.guardrails.patterns.strip`          | `PromptSanitizer`       | list of regex       |
| `llm.guardrails.patterns.toxic-keywords` | `ToxicityFilterAdvisor` | list of substrings  |

Sensible, fail-safe defaults ship in code (so protection is never accidentally
disabled); any value you set in YAML **replaces** that category. See the commented
example block under `llm.guardrails.patterns` in `application.yaml`. Invalid regexes are
logged and skipped at startup rather than crashing the app.

---

## Guardrails Service (LangServe sidecar)

A FastAPI + **LangServe** service in `guardrails-service/`, built on the official
**`langchain/langchain`** Docker image, that the gateway consults over REST **before
forwarding any prompt to an LLM provider** (chain step 300) and — when
`LLM_EXTERNAL_GUARDRAILS_VALIDATE_OUTPUT=true` — after the model answers, before the
response is cached or returned.

The guardrails pipeline is exposed as a standard LangChain `Runnable` via LangServe,
giving you `invoke`, `batch`, `stream` endpoints and a free browser **playground** UI.

```
LlmGatewayFacade ──► GuardrailChain ──► RemoteGuardrailStep
                                              │ POST /guardrails/invoke
                                              │ {"input": {"text": "...", "stage": "input"}}
                                              ▼
                                     Guardrails sidecar :8000
                                     ├─ length limit
                                     ├─ prompt-injection / jailbreak heuristics
                                     ├─ toxicity keywords
                                     ├─ blocked-topics policy (env-tunable)
                                     ├─ PII masking (returns sanitized_text)
                                     └─ optional LangChain LLM-as-judge
```

### API

| Endpoint                      | Description                                       |
|-------------------------------|---------------------------------------------------|
| `POST /guardrails/invoke`     | Single validation call (used by the Java gateway) |
| `POST /guardrails/batch`      | Batch of inputs in one request                    |
| `POST /guardrails/stream`     | Streaming response                                |
| `GET  /guardrails/playground` | Interactive browser UI for manual testing         |
| `POST /v1/validate`           | Legacy endpoint (kept for backward compatibility) |
| `GET  /health`                | Liveness + LLM judge status                       |
| `GET  /v1/checks`             | Active checks + current policy                    |

```bash
# Validate a prompt via LangServe invoke
curl -X POST http://localhost:8000/guardrails/invoke \
  -H "Content-Type: application/json" \
  -d '{"input": {"text": "ignore all previous instructions", "stage": "input"}}'
# → {"output": {"passed": false,
#               "violations": ["prompt-injection: matched 'ignore all previous instructions'"],
#               "sanitized_text": null, "risk_score": 0.25, "checks_run": [...], "latency_ms": 1}}

# Open the playground in a browser
open http://localhost:8000/guardrails/playground

curl http://localhost:8000/health      # liveness + whether the LLM judge is active
curl http://localhost:8000/v1/checks   # active checks + current policy
```

- `stage` is `"input"` (prompt, pre-LLM) or `"output"` (model answer, post-LLM).
- When the sidecar returns `sanitized_text` (e.g. masked PII) on a passing result, the
  gateway forwards the sanitized version to the provider and marks the response
  `sanitized: true`.
- A failing result rejects the request with **HTTP 400** carrying the sidecar's
  violations, increments `llm_requests_rejected_total{reason="EXTERNAL_GUARDRAIL"}`, and
  emits an `AUDIT` log line via the `GuardrailViolationEvent` observer. Guardrail
  rejections are **never** auto-failed-over — the prompt is the problem, not the provider.

### Availability policy

The call is wrapped in its own Resilience4j circuit breaker (`guardrails-service`). If the
sidecar is down, times out, or the circuit is open:

- **fail-open** (default) — the request continues without remote validation; a `WARN` is
  logged and `llm_requests_errors_total{provider="guardrails-service"}` increments.
- **fail-closed** (`LLM_EXTERNAL_GUARDRAILS_FAIL_OPEN=false`) — the request is rejected;
  choose this when policy enforcement matters more than availability.

The in-process chain steps (100/200) still run either way, so baseline protection never
depends on the sidecar.

---

## Observability

> **Full setup guide:** see **[PROMETHEUS_GRAFANA_SETUP.md](PROMETHEUS_GRAFANA_SETUP.md)**
> for the end-to-end Prometheus + Grafana walkthrough, PromQL examples, and troubleshooting.

### Prometheus metrics

Exposed at `GET /llm/v1/actuator/prometheus`.

> **Note the `/llm/v1` prefix** — `spring.webflux.base-path=/llm/v1` also prefixes the actuator
> endpoints, so the scrape path is `/llm/v1/actuator/prometheus` (configured in
> `observability/prometheus.yml`).

Custom application metrics (emitted by `LlmMetricsService` + the `@Timed` facade):

| Metric (Prometheus name)         | Type              | Labels                          | Meaning                                                          |
|----------------------------------|-------------------|---------------------------------|------------------------------------------------------------------|
| `llm_provider_calls_total`       | counter           | `provider`, `model`, `outcome`  | **Calls routed to each provider** (success/error)                |
| `llm_requests_total`             | counter           | `provider`, `cache_hit`         | Total requests incl. cache hits                                  |
| `llm_requests_errors_total`      | counter           | `provider`, `error_type`        | Errors by type                                                   |
| `llm_requests_rejected_total`    | counter           | `provider`, `reason`            | Requests blocked by guardrails                                   |
| `llm_request_latency_seconds`    | histogram         | `provider`                      | Per-provider LLM call latency (p50/p95/p99)                      |
| `llm_tokens_total`               | counter           | `provider`, `model`, `type`     | Token usage (prompt/completion/total)                            |
| `llm_prompt_length_chars`        | summary           | `provider`                      | Prompt size distribution                                         |
| `llm_gateway_execution_seconds`  | timer (`@Timed`)  | `operation`                     | **Gateway turnaround time** (execute / failover / auto-failover) |
| `http_server_requests_seconds`   | timer (built-in)  | `uri`, `method`, `status`       | **REST API turnaround time per endpoint**                        |

Histogram buckets are enabled for latency metrics (`management.metrics.distribution.percentiles-histogram`)
so Grafana can compute percentiles.

### Grafana dashboard

An importable dashboard lives at
`observability/grafana/dashboards/grafana-dashboard-llm-gateway.json`. With Docker Compose
it is **auto-provisioned** (appears under *Dashboards → LLM Gateway* at <http://localhost:3000>,
`admin`/`admin`). It visualises calls per provider, REST API turnaround time, latency
percentiles, token usage, errors, circuit-breaker state, and JVM/system health.

### Distributed Tracing

Every request creates an OTEL span `llm.request`. Trace and span IDs appear in every log line via MDC. Structured extraction creates a separate `llm.structured` span.

### Actuator endpoints

| Endpoint                              | Description                                           |
|---------------------------------------|-------------------------------------------------------|
| `/llm/v1/actuator/health`             | Spring Boot health (includes Redis probe)             |
| `/llm/v1/actuator/health/readiness`   | k8s `readinessProbe` — Redis + R2DBC + DB included    |
| `/llm/v1/actuator/health/liveness`    | k8s `livenessProbe` — process liveness only           |
| `/llm/v1/actuator/metrics`            | Browse individual metrics (JSON)                      |
| `/llm/v1/actuator/prometheus`         | Prometheus scrape                                     |
| `/llm/v1/actuator/circuitbreakers`    | Resilience4j state                                    |
| `/llm/v1/actuator/loggers`            | Runtime log level changes                             |

Example k8s deployment probes:

```yaml
readinessProbe:
  httpGet: { path: /llm/v1/actuator/health/readiness, port: 8080 }
  initialDelaySeconds: 10
livenessProbe:
  httpGet: { path: /llm/v1/actuator/health/liveness, port: 8080 }
  initialDelaySeconds: 20
```

---

## Project Structure

```
src/
└── main/
    ├── java/com/llm/gateway/llm_gateway/
    │   ├── cache/         PromptCacheService, RedisChatMemoryRepository
    │   ├── config/        ChatClientConfig, LlmRouterConfig, ObservabilityConfig, GuardrailPatternProperties, ...
    │   ├── dto/           LlmRequest, LlmResponse, LlmProvider, StructuredLlmResponse
    │   ├── exception/     GlobalExceptionHandler, LLMProviderNotSupportedException
    │   ├── facade/        LlmGatewayFacade (Facade), LlmProviderRegistry (Registry),
    │   │                  LlmServiceProvider (Strategy SPI)
    │   ├── guardrail/     8 advisor classes + AdvisorUtils (shared extraction helpers)
    │   │   ├── chain/     GuardrailChain + GuardrailStep (Chain of Responsibility):
    │   │   │              PromptSanitizationStep, SensitiveDataRedactionStep, RemoteGuardrailStep
    │   │   ├── event/     GuardrailViolationEvent + audit listener (Observer)
    │   │   └── remote/    RemoteGuardrailClient (Adapter), RemoteGuardrailProperties
    │   ├── handler/       LlmHandler, LlmStreamHandler
    │   ├── image/         ImageHandler, ImageService
    │   ├── observability/ LlmMetricsService
    │   ├── security/      SecurityConfig (Keycloak/OAuth2), PromptSanitizer, SensitiveDataRedactor, ...
    │   ├── service/       AbstractRestLlmService (Template Method),
    │   │                  OpenAiService, AnthropicClaudeService, OllamaService,
    │   │                  GoogleGeminiService, CohereService, HuggingFaceService
    │   ├── template/      PromptTemplateService
    │   └── tools/         GatewayTools
    │
    └── resources/
        ├── application.yaml
        ├── db/migration/
        │   ├── V1__create_api_keys.sql   (historical — table dropped in V4)
        │   ├── V2__seed_api_keys.sql     (historical — table dropped in V4)
        │   ├── V3__create_request_log.sql Audit log table
        │   └── V4__drop_api_keys.sql     Drops api_keys — auth now lives in Keycloak
        └── prompts/
            ├── system-openai.st
            ├── system-anthropic.st
            ├── system-ollama.st
            ├── system-default.st
            └── assistant-starter.st

observability/
├── prometheus.yml                       Prometheus scrape config (/llm/v1/actuator/prometheus)
├── tempo.yml  ·  loki-config.yaml        Trace + log backends
└── grafana/
    ├── provisioning/
    │   ├── datasources/datasources.yml   Prometheus + Tempo + Loki data sources
    │   └── dashboards/dashboards.yml      Auto-load provider for dashboards
    └── dashboards/
        └── grafana-dashboard-llm-gateway.json   Importable LLM Gateway dashboard
guardrails-service/                        LangServe guardrails sidecar (Docker)
├── Dockerfile                             FROM langchain/langchain + FastAPI + LangServe
├── app.py                                 /guardrails/invoke|batch|stream|playground, /v1/validate, /health
└── requirements.txt
docker/
├── keycloak/
│   └── llm-gateway-realm.json             Realm + client + roles, auto-imported on first boot
├── prometheus.yml
└── tempo.yaml
Dockerfile                                 Multi-stage build for the main app
docker-compose.yml
PROMETHEUS_GRAFANA_SETUP.md               Prometheus + Grafana setup guide
```

---

## Insomnia Collection

Import `insomnia-collection.json` into Insomnia to get all endpoints pre-configured.

**Environment variables in the collection:**

| Variable           | Default                               | Description                                                                                  |
|--------------------|---------------------------------------|----------------------------------------------------------------------------------------------|
| `base_url`         | `http://localhost:8080/llm/v1`        | Gateway base URL                                                                             |
| `access_token`     | `PASTE_A_KEYCLOAK_JWT_HERE`           | Bearer token — paste a fresh Keycloak access token (see [Getting a token](#getting-a-token)) |
| `session_id`       | `test-session-001`                    | Chat session ID                                                                              |
| `openai_model`     | `gpt-4o`                              | OpenAI model                                                                                 |
| `anthropic_model`  | `claude-3-5-sonnet-20241022`          | Claude model                                                                                 |
| `google_model`     | `gemini-1.5-pro-latest`               | Gemini model                                                                                 |
| `cohere_model`     | `command-r-plus`                      | Cohere model                                                                                 |
| `hf_model`         | `mistralai/Mistral-7B-Instruct-v0.1`  | HuggingFace model                                                                            |

**Folders in the collection:**

| Folder              | Requests                                                       |
|---------------------|----------------------------------------------------------------|
| 📋 Gateway          | Health, List Providers, List Models                            |
| 🤖 OpenAI           | Query, Chat, Stream, Structured Output, Template Vars, Prefill |
| 🧠 Anthropic        | Query, Chat, Stream, JSON Prefill                              |
| 🦙 Ollama           | Query, Chat, Stream                                            |
| ✨ Google Gemini     | Query, Chat, Template Vars                                     |
| 🌊 Cohere           | Query, Chat, Stream                                            |
| 🤗 HuggingFace      | Query, Chat, Stream                                            |
| 🔄 Failover         | Default chain, Custom chain, All six providers                 |
| 📝 Prompt Templates | Template vars, Raw system_prompt                               |
| 💬 Chat Sessions    | Turn 1, Turn 2, Delete session                                 |

---

## Provider API Details

### OpenAI / Anthropic / Ollama
Implemented via Spring AI `ChatClient` — full guardrail chain, chat memory, streaming, structured output, tool calling.

### Google Gemini
Uses the Google Generative Language REST API (`v1beta`). System prompts are passed via `systemInstruction` (Gemini's native field). Supports `gemini-1.5-pro-latest`, `gemini-1.5-flash-latest`, `gemini-2.0-flash`, and other Gemini models.

**Required env var:** `GOOGLE_API_KEY`

### Cohere
Uses the Cohere v2 chat API (`https://api.cohere.com/v2/chat`) with the messages format (system + user turns). Supports `command-r-plus`, `command-r`, `command-light`.

**Required env var:** `COHERE_API_KEY`

### HuggingFace
Uses the HuggingFace Serverless Inference API with its OpenAI-compatible endpoint (`https://api-inference.huggingface.co/v1/chat/completions`). Works with any model on the HuggingFace Hub that supports the chat completion task (e.g. Mistral, Llama, Qwen, Phi).

**Required env var:** `HUGGINGFACE_API_KEY`

---

## Building

```bash
mvn clean install          # compile + test
mvn spring-boot:run        # run locally
mvn clean package -DskipTests && java -jar target/llm-gateway-*.jar
```

### Code formatting (Spotless)

Java sources are auto-formatted with [Spotless](https://github.com/diffplug/spotless) +
Google Java Format, checked on every `mvn verify`:

```bash
mvn spotless:check   # fails the build if files aren't formatted
mvn spotless:apply   # reformats in place
```

> google-java-format relies on internal `javac` APIs that aren't yet compatible with JDK 25's
> compiler. Run `spotless:apply`/`spotless:check` under JDK 21+ (e.g.
> `JAVA_HOME=$(/usr/libexec/java_home -v21) mvn spotless:apply`) — the formatted output is
> plain Java source with no JDK-version coupling, so this only affects which JDK runs the
> formatter, not which JDK compiles/runs the app.

### Dependency vulnerability scanning (OWASP)

Not part of the default build (needs network access to the NVD feed, ideally with an
`NVD_API_KEY`):

```bash
mvn -P security-scan verify
# report: target/dependency-check-report.html
```

### Docker image

```bash
docker build -t llm-gateway:local \
  --secret id=maven_settings,src=$HOME/.m2/settings.xml .
docker run -p 8080:8080 --env-file .env llm-gateway:local
```

The build stage resolves the private `super-pom`/`learning-bom` parent from your configured
Maven repository — pass it in via the `maven_settings` BuildKit secret, not baked into the image.

---

## Technology Deep Dive

A plain-English explanation of every technology in this repo — what it is and exactly how this project uses it.

---

### Spring Boot 4 + Spring WebFlux

**What it is:** Spring Boot is an opinionated Java framework for building production-ready applications with minimal configuration. Spring WebFlux is its reactive, non-blocking web layer built on Project Reactor.

**How it's used here:** The entire gateway is reactive. Every HTTP handler (`LlmHandler`) returns `Mono<ServerResponse>` instead of a plain object, which means the server thread is never blocked waiting for an LLM response, Redis, or Postgres. A single thread can serve thousands of concurrent requests while all the I/O happens asynchronously. The reactive pipeline looks like:

```
HTTP request → WebFlux router → LlmHandler → boundedElastic scheduler
                                                    │
                                        (blocking LLM call offloaded here)
                                                    │
                                              Mono<LlmResponse> → HTTP response
```

---

### Spring AI

**What it is:** Spring AI is the official Spring abstraction over LLM providers — it gives a single `ChatClient` interface regardless of which model you're calling.

**How it's used here:** Each provider (OpenAI, Anthropic, Ollama, etc.) is autowired as a `ChatClient` bean. Spring AI also powers the advisor chain (toxicity filter, PII redaction, memory, metrics) that wraps every `ChatClient` call. Switching a provider is a config change, not a code change.

---

### Keycloak

**What it is:** An open-source identity and access management server (originally a JBoss/Red Hat project) implementing OAuth2/OIDC — issues, signs, and validates JWT access tokens against realms, clients, users, and roles.

**How it's used here:** Spring Security's OAuth2 **resource server** support (`spring-boot-starter-oauth2-resource-server`) validates every incoming `Authorization: Bearer <jwt>` against Keycloak's JWKS endpoint — the gateway never talks to Keycloak synchronously per-request beyond that signature check, and never stores credentials itself. `docker-compose.yml` runs Keycloak in dev mode and imports the `llm-gateway` realm (`docker/keycloak/llm-gateway-realm.json`) automatically. See [Security — Keycloak / OAuth2 Authentication](#security--keycloak--oauth2-authentication) for the full flow.

---

### PostgreSQL 18

**What it is:** A relational database.

**How it's used here:** Stores the API key registry — a table of SHA-256 hashed keys used to authenticate gateway callers when `GATEWAY_AUTH_ENABLED=true`. Flyway manages the schema. At runtime the gateway uses R2DBC (reactive) to query the table; Flyway uses a separate blocking JDBC connection only at startup.

---

### pgAdmin 4

**What it is:** A web-based GUI for PostgreSQL.

**How it's used here:** Runs at **http://localhost:5050** in Docker desktop mode (no login required). Use it to browse the `spring_ai` database, inspect the `request_log` audit table, run ad-hoc SQL, and monitor query activity during development.

```
Browser → pgAdmin :5050 → postgres :5432 (service name inside Docker network)
```

To connect: Register Server → Host: `postgres`, Port: `5432`, DB: `spring_ai`, User/Pass: `postgres`.

---

### Redis

**What it is:** An in-memory key-value store used as a cache and message broker.

**How it's used here — two roles:**

1. **Prompt cache** — before calling any LLM, the facade checks Redis for an identical prompt (SHA-256 keyed). A cache hit returns the stored response instantly, skipping the LLM entirely. TTL is configurable via `LLM_CACHE_TTL_MINUTES`.

2. **Chat memory** — multi-turn `/chat` conversations store their history in Redis (via Spring AI's `ChatMemoryRepository`). Each session is keyed by `session_id` and expires after `LLM_CHAT_MEMORY_TTL_HOURS`.

```
POST /chat  →  Redis GET session:{id}   (load history)
            →  LLM call with history
            →  Redis SET session:{id}   (save updated history)
```

---

### RedisInsight

**What it is:** A web-based GUI for Redis, built by Redis Ltd.

**How it's used here:** Runs at **http://localhost:5540**. Connect with Host: `redis`, leave username/password blank. Use it to inspect cached prompts, browse chat session keys, monitor memory usage, and watch live commands during debugging.

---

### Flyway

**What it is:** A database migration tool — it tracks and applies versioned SQL scripts to keep your schema in sync with the application.

**How it's used here:** On every application startup, Flyway connects via JDBC (separate from the R2DBC runtime connection) and runs any pending scripts from `src/main/resources/db/migration/`. The migration history is stored in `flyway_schema_history_gateway`. This ensures the `request_log` table and any future schema changes are applied automatically in every environment.

---

### R2DBC

**What it is:** Reactive Relational Database Connectivity — a non-blocking alternative to JDBC for relational databases.

**How it's used here:** `RequestLogService` uses R2DBC's reactive PostgreSQL driver to write audit rows to the `request_log` table without blocking a thread. This keeps the full request pipeline reactive. Authentication itself no longer touches Postgres at all — JWT validation happens entirely against Keycloak's JWKS, with no database round-trip.

---

### Resilience4j

**What it is:** A lightweight fault-tolerance library for Java — provides circuit breaker, retry, rate limiter, and bulkhead patterns.

**How it's used here — three patterns:**

| Pattern             | Instance             | What it protects                                                           |
|---------------------|----------------------|----------------------------------------------------------------------------|
| **Circuit Breaker** | `llm-gateway`        | Wraps LLM provider calls — opens after 50% failure rate, pauses for 30s    |
| **Circuit Breaker** | `guardrails-service` | Wraps the LangServe sidecar call — opens after 50% failure, pauses for 20s |
| **Retry**           | `llm-gateway`        | Retries failed LLM calls up to 3× with exponential backoff (2s→4s→8s)      |
| **Rate Limiter**    | `llm-gateway`        | 60 requests/minute per gateway instance                                    |

When the guardrails circuit is open the gateway either fails-open (continues without remote validation) or fails-closed (rejects the request) based on `LLM_EXTERNAL_GUARDRAILS_FAIL_OPEN`.

---

### Micrometer + OpenTelemetry (OTEL)

**What it is:** Micrometer is a metrics facade for JVM apps (like SLF4J but for metrics). OpenTelemetry is the industry standard for distributed tracing and telemetry.

**How it's used here:**

- **Metrics** — `LlmMetricsService` uses Micrometer to emit counters and histograms (`llm_provider_calls_total`, `llm_request_latency_seconds`, etc.). The Micrometer Prometheus registry exposes them at `/llm/v1/actuator/prometheus`.
- **Traces** — the `micrometer-tracing-bridge-otel` library connects Micrometer's tracing API to the OpenTelemetry SDK, which exports spans to Tempo via OTLP HTTP (`http://tempo:4318/v1/traces`). Every request gets a `traceId` that appears in logs and in Tempo.

---

### Prometheus

**What it is:** An open-source metrics collection and alerting system. It scrapes HTTP endpoints for metrics data.

**How it's used here:** Configured in `observability/prometheus.yml` to scrape the gateway's `/llm/v1/actuator/prometheus` endpoint every 15 seconds. Stores the time-series data and makes it queryable via PromQL. Grafana reads from Prometheus to build dashboards.

```
Spring Boot app :8080/llm/v1/actuator/prometheus
        ↑  (scrape every 15s)
   Prometheus :9090
        ↑  (PromQL queries)
   Grafana :3000
```

---

### Grafana Tempo

**What it is:** A distributed tracing backend — stores and queries traces from instrumented applications.

**How it's used here:** The gateway sends every request trace to Tempo over OTLP HTTP on port `4318`. Each trace is a tree of spans — one span per significant operation (guardrails check, Redis lookup, LLM call, etc.) with its own start time, duration, and tags. In Grafana you can paste a `traceId` from a log line and see the full waterfall of that request, making it easy to identify exactly which step was slow.

```
LLM Gateway  →  OTLP/HTTP :4318  →  Tempo :3200  →  Grafana (flame graph / waterfall)
```

---

### Grafana Loki

**What it is:** A log aggregation system designed to work alongside Prometheus and Tempo. Unlike Elasticsearch, Loki indexes only log labels (not the full text), making it very storage-efficient.

**How it's used here:** Application logs are shipped to Loki and queryable in Grafana using LogQL. Because every log line includes `traceId` and `spanId`, you can jump directly from a Grafana trace span to the exact log lines emitted during that span — correlating traces and logs in one place.

---

### Grafana

**What it is:** An open-source observability platform for visualising metrics, logs, and traces.

**How it's used here:** Runs at **http://localhost:3000** (admin/admin). Provisioned automatically with three datasources (Prometheus, Tempo, Loki) and the pre-built LLM Gateway dashboard. The dashboard shows:
- Request rate and error rate per provider
- p50/p95/p99 latency histograms
- Cache hit rate
- Guardrail rejection rate
- Circuit breaker state
- JVM memory and GC metrics

---

### LangChain + LangServe

**What it is:** LangChain is a Python framework for building LLM pipelines. LangServe is its REST deployment layer — it wraps any LangChain `Runnable` as a FastAPI service with standard `invoke`, `batch`, `stream`, and `playground` endpoints.

**How it's used here:** The `guardrails-service/` sidecar runs a FastAPI + LangServe app that the Java gateway calls before every LLM request. It runs a pipeline of checks (prompt injection, toxicity, PII masking, blocked topics, optional LLM-as-judge) and returns a structured result. The Java gateway calls `POST /guardrails/invoke`; the playground at `/guardrails/playground` lets you test prompts manually in a browser.

---

### FastAPI + Uvicorn

**What it is:** FastAPI is a modern Python web framework with automatic OpenAPI docs and Pydantic validation. Uvicorn is its ASGI server.

**How it's used here:** Powers the guardrails sidecar alongside LangServe. The legacy `/v1/validate` and `/health` endpoints are plain FastAPI routes; the LangServe routes (`/guardrails/*`) are added on top of the same `FastAPI` app instance.

---

### Docker Compose

**What it is:** A tool for defining and running multi-container Docker applications from a single YAML file.

**How it's used here:** `docker-compose.yml` defines all 9 services as a local development stack. Services reference each other by name (e.g. the gateway connects to `postgres:5432`, pgAdmin connects to `postgres`). Healthchecks on Postgres and Redis ensure dependent services only start when their dependency is truly ready.
