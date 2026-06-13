# LLM Gateway

A production-ready, reactive Spring Boot gateway that provides a single unified API for multiple LLM providers (OpenAI, HuggingFace, Cohere, Anthropic Claude, Ollama). Handles routing, failover, multi-turn chat memory, prompt safety, guardrails (in-process + a LangChain-based guardrails sidecar), caching, API-key authentication, and full observability out of the box.

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

---

## Architecture Overview

```
Client
  │   X-API-Key header (when auth enabled)
  ▼
┌─────────────────────────────────────────────────────────────────┐
│  Spring Security WebFlux                                        │
│  ApiKeyReactiveAuthManager → PostgreSQL api_keys table          │
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
│                      │   │  9-Step Guardrail Chain           │  │
│                      │   │  ① PromptGuardAdvisor (logging)   │  │
│                      │   │  ② ToxicityFilterAdvisor          │  │
│                      │   │  ③ PiiRedactionAdvisor            │  │
│                      │   │  ④ TopicFilterAdvisor             │  │
│                      │   │  ⑤ MetricsAdvisor                 │  │
│                      │   │  ⑥ ChatMemoryAdvisor (Redis)      │  │
│                      │   │  ⑦ SimpleLoggerAdvisor            │  │
│                      │   │  ⑧ ResponseFormatAdvisor          │  │
│                      │   │  ⑨ HallucinationMonitorAdvisor    │  │
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

| Pattern | Where | Why |
|---------|-------|-----|
| **Facade** | `facade/LlmGatewayFacade` | Single entry point hiding the pipeline (guardrails → cache → tracing → provider → metrics) from handlers |
| **Strategy** | `facade/LlmServiceProvider` + 6 implementations | Each provider is an interchangeable strategy resolved by name at runtime |
| **Factory Method / Registry** | `facade/LlmProviderRegistry` | Discovers every `LlmServiceProvider` bean and resolves/validates providers; adding a provider needs zero gateway changes (OCP) |
| **Chain of Responsibility** | `guardrail/chain/GuardrailChain` + `GuardrailStep`s | Ordered, pluggable inbound guardrail pipeline (sanitization → PII redaction → remote guardrails); any step can rewrite the prompt or reject the request |
| **Template Method** | `service/AbstractRestLlmService` | Fixes the REST-call skeleton (render system prompt → POST → parse → never throw); Google/Cohere/HuggingFace implement only the hooks (endpoint, headers, payload, parsing) |
| **Adapter** | `guardrail/remote/RemoteGuardrailClient` | Adapts the LangServe sidecar's REST/JSON API (`POST /guardrails/invoke`) to a typed Java interface |
| **Observer** | `guardrail/event/GuardrailViolationEvent` + listener | Guardrail rejections publish events; audit logging/alerting subscribe without coupling to the chain |
| **Decorator** | Spring AI advisor chain (`guardrail/*Advisor`) | Each advisor wraps the ChatClient call, adding behaviour (toxicity, PII, metrics, memory) transparently |
| **Builder** | Lombok `@Builder` on `LlmRequest`/`LlmResponse` | Safe construction of many-field immutable-style DTOs |
| **Proxy** | Resilience4j `@CircuitBreaker`/`@Retry` (AOP) | Cross-cutting resilience added via dynamic proxies, not in business code |
| **Singleton** | Spring-managed beans | All services/components are container-scoped singletons (no hand-rolled singletons) |

---

## Tech Stack

| Layer            | Technology                                                           |
|------------------|----------------------------------------------------------------------|
| Runtime          | Java 21, Spring Boot 4.0.6                                           |
| Web              | Spring WebFlux (reactive, non-blocking)                              |
| Security         | Spring Security WebFlux + PostgreSQL API key table                   |
| LLM Integration  | Spring AI 2.0.0-M8                                                   |
| LLM Providers    | OpenAI, Anthropic Claude, Ollama, Google Gemini, Cohere, HuggingFace |
| Guardrails       | In-process chain + LangChain/FastAPI sidecar (REST, Docker)          |
| Cache + Memory   | Redis (Spring Data Redis / Lettuce)                                  |
| Database         | PostgreSQL 18 (R2DBC reactive + JDBC for Flyway)                     |
| Migrations       | Flyway                                                               |
| Resilience       | Resilience4j (Circuit Breaker, Retry, Rate Limiter)                  |
| Observability    | Micrometer + OTEL, Prometheus, Grafana Tempo                         |
| Build            | Maven 3.9+                                                           |

---

## Features

- **Multi-provider routing** — single API, routed to OpenAI, Anthropic, Ollama, Google Gemini, Cohere, or HuggingFace
- **Failover chain** — tries the next provider automatically on failure
- **Multi-turn chat** — Redis-backed conversation memory per `session_id`
- **Session management** — `DELETE /sessions/{sessionId}` clears history
- **Prompt template system** — per-provider `.st` files with variable substitution
- **Assistant message prefill** — steer response format without consuming a user turn
- **API key authentication** — Spring Security WebFlux backed by a PostgreSQL table; SHA-256 hashed keys, expiry support, `last_used` tracking
- **9-step guardrail chain** — prompt safety, PII redaction, toxicity filter, topic restriction, hallucination monitoring, response format validation
- **External guardrails service** — LangChain-based sidecar (Docker) consulted over REST **before every LLM call** (and optionally on responses); injection/jailbreak heuristics, toxicity, PII masking, topic policy, optional LLM-as-judge; fail-open/fail-closed policy + dedicated circuit breaker
- **GoF design patterns throughout** — Facade, Strategy, Registry, Chain of Responsibility, Template Method, Adapter, Observer, Decorator, Builder, Proxy (see [Design Patterns](#design-patterns-gof))
- **Sensitive-data guard (all providers)** — provider-agnostic PII + secret redaction applied in the facade on both request and response, so nothing sensitive is sent to an LLM, cached, or logged
- **Externalised guardrail patterns** — all injection / PII / toxicity pattern lists tunable in YAML (`llm.guardrails.patterns.*`) without code changes
- **Redis prompt cache** — SHA-256 keyed (includes system prompt, template vars, assistant message), configurable TTL
- **Streaming** — SSE token streaming per provider
- **Structured output** — typed Java record extraction from LLM responses (via facade for full observability)
- **Request timeout** — configurable per-request timeout with 504 response
- **X-Request-ID propagation** — correlation ID accepted from callers and echoed in response headers and body
- **Real health check** — `/health` probes Redis connectivity
- **Provider enable/disable** — `@ConditionalOnProperty` per service; disabled providers never start
- **Distributed tracing** — OTEL spans with trace/span IDs in every log line
- **Prometheus metrics + Grafana dashboard** — calls per provider, REST API turnaround time (`@Timed` + `http.server.requests`), latency percentiles, token usage, cache hits, rejections, errors; importable/auto-provisioned dashboard included

---

## Prerequisites

| Requirement       | Version |
|-------------------|---------|
| Java              | 21+     |
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
docker compose up -d postgres redis guardrails
```

The `guardrails` service is built locally from `guardrails-service/` (base image:
`langchain/langchain`) the first time you run this.

Flyway runs automatically on startup and creates the `api_keys` table with two seed keys:

| Key name        | Raw value (local dev only)   |
|-----------------|------------------------------|
| Development Key | `llm-gateway-dev-key-2026`   |
| Admin Key       | `llm-gateway-admin-key-2026` |

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

```bash
# Without auth (default)
curl -X POST http://localhost:8080/llm/query \
  -H "Content-Type: application/json" \
  -d '{"prompt": "Hello!", "provider": "OPENAI"}'

# With auth enabled
curl -X POST http://localhost:8080/llm/query \
  -H "Content-Type: application/json" \
  -H "X-API-Key: llm-gateway-dev-key-2026" \
  -d '{"prompt": "Hello!", "provider": "OPENAI"}'
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

| Service            | Port | Purpose                                        |
|--------------------|------|------------------------------------------------|
| Guardrails sidecar | 8000 | LangServe guardrails API (`POST /guardrails/invoke`, playground at `/guardrails/playground`) |
| PostgreSQL         | 5432 | API key registry                               |
| Redis              | 6379 | Prompt cache + chat memory                     |
| Prometheus         | 9090 | Metrics                                        |
| Grafana            | 3000 | Dashboards (admin/admin)                       |
| Grafana Tempo      | 4318 | OTLP trace collector                           |

---

## Configuration Reference

All values can be overridden via environment variables.

### Server

| Property                   | Env Var | Default |
|----------------------------|---------|---------|
| `server.port`              | —       | `8080`  |
| `spring.webflux.base-path` | —       | `/llm`  |

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

| Env Var                    | Default | Description                                            |
|----------------------------|---------|--------------------------------------------------------|
| `GUARDRAILS_BLOCKED_TOPICS`| _(empty)_ | Comma-separated restricted topics                    |
| `GUARDRAILS_MAX_LENGTH`    | `10000` | Max accepted text length                               |
| `GUARDRAILS_LLM_CHECK`     | `false` | Enable LangChain LLM-as-judge (needs `OPENAI_API_KEY`) |

### Security

| Env Var                    | Default | Description                   |
|----------------------------|---------|-------------------------------|
| `GATEWAY_AUTH_ENABLED`               | `false` | Set `true` in production              |
| `LLM_SANITIZATION_ENABLED`           | `true`  | Prompt injection detection            |
| `LLM_MAX_PROMPT_LENGTH`              | `10000` | Hard cap on prompt characters         |
| `LLM_SENSITIVE_DATA_ENABLED`         | `true`  | PII + secret redaction (all providers)|
| `LLM_SENSITIVE_DATA_REDACT_PROMPT`   | `true`  | Redact before sending to provider     |
| `LLM_SENSITIVE_DATA_REDACT_RESPONSE` | `true`  | Redact before returning to caller     |

### Observability

| Env Var                       | Default                 |
|-------------------------------|-------------------------|
| `OTEL_EXPORTER_OTLP_ENDPOINT` | `http://localhost:4318` |

---

## Security — API Key Authentication

### How it works

1. Client sends `X-API-Key: <raw-key>` header
2. Spring Security's `AuthenticationWebFilter` extracts the key
3. `ApiKeyService` hashes it with SHA-256 and queries the `api_keys` PostgreSQL table
4. If the key exists, is enabled, and is not expired → request is authenticated
5. `last_used` timestamp is updated asynchronously (non-blocking)
6. If no key or invalid key → **HTTP 401**
7. Actuator endpoints (`/actuator/**`) are always permitted without a key

### Database schema

```sql
CREATE TABLE api_keys (
    id          BIGSERIAL     PRIMARY KEY,
    name        VARCHAR(100)  NOT NULL,
    key_hash    CHAR(64)      NOT NULL,   -- SHA-256(raw_key) hex
    client_id   VARCHAR(100),
    enabled     BOOLEAN       NOT NULL DEFAULT TRUE,
    created_at  TIMESTAMPTZ   NOT NULL DEFAULT NOW(),
    expires_at  TIMESTAMPTZ,              -- NULL = never expires
    last_used   TIMESTAMPTZ,
    CONSTRAINT api_keys_hash_unique UNIQUE (key_hash)
);
```

Flyway manages the schema. Migrations live in `src/main/resources/db/migration/`.

### Seeded keys (local development)

Migration `V2__seed_api_keys.sql` pre-loads two ready-to-use keys so you can exercise the gateway with auth enabled without minting your own. Send the **raw value** in the `X-API-Key` header — the gateway hashes it and matches the SHA-256 stored in `key_hash`.

| Name            | `client_id`    | Raw key (`X-API-Key`)        | Purpose                                                                                                     |
|-----------------|----------------|------------------------------|-------------------------------------------------------------------------------------------------------------|
| Development Key | `dev-client`   | `llm-gateway-dev-key-2026`   | Day-to-day local testing — the key you'd normally use from Insomnia/curl.                                   |
| Admin Key       | `admin-client` | `llm-gateway-admin-key-2026` | Represents a separate, higher-trust caller, distinguished by its own `client_id` for audit/log attribution. |

Both keys are functionally equivalent today (the gateway does not yet enforce per-role authorization) — the difference is the **`client_id`** each authenticates as, which is what shows up in `last_used` tracking and request logs. They are **local-development credentials only**; rotate or remove them before any real deployment.

### Adding a new API key

```sql
-- Use PostgreSQL's pgcrypto to hash the raw key
INSERT INTO api_keys (name, key_hash, client_id)
VALUES (
    'My Service',
    encode(digest('your-raw-api-key-here', 'sha256'), 'hex'),
    'my-service'
);
```

### Enabling auth

```bash
export GATEWAY_AUTH_ENABLED=true
```

Auth is **disabled by default** so the gateway works out of the box in local development.

### Rotating a key

```sql
-- Disable the old key
UPDATE api_keys SET enabled = FALSE WHERE name = 'My Service';

-- Insert the new key
INSERT INTO api_keys (name, key_hash, client_id)
VALUES ('My Service v2', encode(digest('new-raw-key', 'sha256'), 'hex'), 'my-service');
```

---

## API Documentation

Base URL: `http://localhost:8080/llm`

All request bodies are JSON. All responses are JSON.

**Common request headers:**

| Header                           | Description                                                                          |
|----------------------------------|--------------------------------------------------------------------------------------|
| `Content-Type: application/json` | Required                                                                             |
| `X-API-Key: <key>`               | Required when `GATEWAY_AUTH_ENABLED=true`                                            |
| `X-Request-ID: <uuid>`           | Optional; echoed back as `X-Request-ID` response header and `correlation_id` in body |

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
curl http://localhost:8080/llm/health
```

```json
{ "status": "UP", "service": "LLM Gateway", "version": "2.0.0-spring-ai", "redis": "UP" }
```

Status is `DEGRADED` when Redis is unreachable.

---

### GET `/providers`

```bash
curl http://localhost:8080/llm/providers
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
curl -X POST http://localhost:8080/llm/query \
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
curl -X POST http://localhost:8080/llm/failover \
  -H "Content-Type: application/json" \
  -d '{"prompt": "Hello", "providers": ["OPENAI", "ANTHROPIC", "OLLAMA"]}'
```

Default chain when `providers` is omitted: `openai → anthropic → ollama`.

---

### POST `/chat`

Multi-turn conversation. `session_id` is mandatory.

```bash
# Turn 1
curl -X POST http://localhost:8080/llm/chat \
  -H "Content-Type: application/json" \
  -d '{"prompt": "My name is Alice.", "provider": "OPENAI", "session_id": "alice-001"}'

# Turn 2 — model remembers "Alice"
curl -X POST http://localhost:8080/llm/chat \
  -H "Content-Type: application/json" \
  -d '{"prompt": "What is my name?", "provider": "OPENAI", "session_id": "alice-001"}'
```

---

### DELETE `/sessions/{sessionId}`

Clears all conversation history for a session from Redis.

```bash
curl -X DELETE http://localhost:8080/llm/sessions/alice-001
# → 204 No Content
```

---

### POST `/{provider}/chat`

Per-provider chat via path variable.

```bash
curl -X POST http://localhost:8080/llm/anthropic/chat \
  -H "Content-Type: application/json" \
  -d '{"prompt": "Explain the CAP theorem.", "session_id": "dev-42"}'
```

---

### POST `/{provider}/stream`

Server-Sent Events streaming (`text/event-stream`).

```bash
curl -X POST http://localhost:8080/llm/openai/stream \
  -H "Content-Type: application/json" \
  -H "Accept: text/event-stream" \
  -d '{"prompt": "Write a haiku.", "session_id": "stream-1"}'
```

---

### POST `/openai/extract`

Structured output extraction — response is deserialised into a typed Java record. Runs through the facade for full tracing and circuit-breaker coverage.

```bash
curl -X POST http://localhost:8080/llm/openai/extract \
  -H "Content-Type: application/json" \
  -d '{"prompt": "Extract: meeting on 2026-06-15 at 14:00 in Room 3."}'
```

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

| Order | Step                      | What it does                                                       |
|-------|---------------------------|--------------------------------------------------------------------|
| 100   | `prompt-sanitization`     | Injection/jailbreak regex blocking, strip + whitespace normalising  |
| 200   | `sensitive-data-redaction`| PII/secret masking (`[EMAIL]`, `[API_KEY]`, …)                      |
| 300   | `remote-guardrails`       | REST call to the [LangServe guardrails sidecar](#guardrails-service-langserve-sidecar) |

Adding a step = one `@Component` implementing `GuardrailStep` — no facade changes.

### Level 2 — Spring AI advisor chain (ChatClient providers)

```
INPUT  ──► ① PromptGuardAdvisor        Logging checkpoint (sanitization done upstream)
           ② ToxicityFilterAdvisor     Blocks harmful content → HTTP 400
           ③ PiiRedactionAdvisor       Masks PII before sending; async scan of output
           ④ TopicFilterAdvisor        Blocks restricted topics → HTTP 400
           ⑤ MetricsAdvisor            Records prompt length and per-provider metrics
           ⑥ ChatMemoryAdvisor         Injects Redis conversation history
           ⑦ SimpleLoggerAdvisor       Logs full prompt/response (debug)
      ──► LLM Provider ◄──
           ⑧ ResponseFormatAdvisor     Validates length, refusals, truncation (async)
           ⑨ HallucinationMonitorAdvisor  Heuristic uncertainty scoring (async)
OUTPUT ◄──
```

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

| Env Var                              | Default | Description                              |
|--------------------------------------|---------|------------------------------------------|
| `LLM_SENSITIVE_DATA_ENABLED`         | `true`  | Master switch for the sensitive-data guard |
| `LLM_SENSITIVE_DATA_REDACT_PROMPT`   | `true`  | Redact before sending to the provider    |
| `LLM_SENSITIVE_DATA_REDACT_RESPONSE` | `true`  | Redact before returning to the caller    |

### Tuning guardrail patterns without code changes

All guardrail pattern lists are externalised in `GuardrailPatternProperties`
(`llm.guardrails.patterns.*`) so they can be added/removed/edited purely in
configuration — no recompile:

| Config key                              | Used by                | Form               |
|-----------------------------------------|------------------------|--------------------|
| `llm.guardrails.patterns.sensitive-data`| `SensitiveDataRedactor`| `name → regex` map |
| `llm.guardrails.patterns.injection`     | `PromptSanitizer`      | list of regex      |
| `llm.guardrails.patterns.strip`         | `PromptSanitizer`      | list of regex      |
| `llm.guardrails.patterns.toxic-keywords`| `ToxicityFilterAdvisor`| list of substrings |

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

| Endpoint | Description |
|---|---|
| `POST /guardrails/invoke` | Single validation call (used by the Java gateway) |
| `POST /guardrails/batch` | Batch of inputs in one request |
| `POST /guardrails/stream` | Streaming response |
| `GET  /guardrails/playground` | Interactive browser UI for manual testing |
| `POST /v1/validate` | Legacy endpoint (kept for backward compatibility) |
| `GET  /health` | Liveness + LLM judge status |
| `GET  /v1/checks` | Active checks + current policy |

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

Exposed at `GET /llm/actuator/prometheus`.

> **Note the `/llm` prefix** — `spring.webflux.base-path=/llm` also prefixes the actuator
> endpoints, so the scrape path is `/llm/actuator/prometheus` (configured in
> `observability/prometheus.yml`).

Custom application metrics (emitted by `LlmMetricsService` + the `@Timed` facade):

| Metric (Prometheus name)        | Type             | Labels                         | Meaning |
|---------------------------------|------------------|--------------------------------|---------|
| `llm_provider_calls_total`      | counter          | `provider`, `model`, `outcome` | **Calls routed to each provider** (success/error) |
| `llm_requests_total`            | counter          | `provider`, `cache_hit`        | Total requests incl. cache hits |
| `llm_requests_errors_total`     | counter          | `provider`, `error_type`       | Errors by type |
| `llm_requests_rejected_total`   | counter          | `provider`, `reason`           | Requests blocked by guardrails |
| `llm_request_latency_seconds`   | histogram        | `provider`                     | Per-provider LLM call latency (p50/p95/p99) |
| `llm_tokens_total`              | counter          | `provider`, `model`, `type`    | Token usage (prompt/completion/total) |
| `llm_prompt_length_chars`       | summary          | `provider`                     | Prompt size distribution |
| `llm_gateway_execution_seconds` | timer (`@Timed`) | `operation`                    | **Gateway turnaround time** (execute / failover / auto-failover) |
| `http_server_requests_seconds`  | timer (built-in) | `uri`, `method`, `status`      | **REST API turnaround time per endpoint** |

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

| Endpoint                        | Description                               |
|---------------------------------|-------------------------------------------|
| `/llm/actuator/health`          | Spring Boot health (includes Redis probe) |
| `/llm/actuator/metrics`         | Browse individual metrics (JSON)          |
| `/llm/actuator/prometheus`      | Prometheus scrape                         |
| `/llm/actuator/circuitbreakers` | Resilience4j state                        |
| `/llm/actuator/loggers`         | Runtime log level changes                 |

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
    │   ├── guardrail/     9 advisor classes + AdvisorUtils (shared extraction helpers)
    │   │   ├── chain/     GuardrailChain + GuardrailStep (Chain of Responsibility):
    │   │   │              PromptSanitizationStep, SensitiveDataRedactionStep, RemoteGuardrailStep
    │   │   ├── event/     GuardrailViolationEvent + audit listener (Observer)
    │   │   └── remote/    RemoteGuardrailClient (Adapter), RemoteGuardrailProperties
    │   ├── handler/       LlmHandler, LlmStreamHandler
    │   ├── image/         ImageHandler, ImageService
    │   ├── observability/ LlmMetricsService
    │   ├── security/      SecurityConfig, ApiKeyService, PromptSanitizer, SensitiveDataRedactor, ...
    │   ├── service/       AbstractRestLlmService (Template Method),
    │   │                  OpenAiService, AnthropicClaudeService, OllamaService,
    │   │                  GoogleGeminiService, CohereService, HuggingFaceService
    │   ├── template/      PromptTemplateService
    │   └── tools/         GatewayTools
    │
    └── resources/
        ├── application.yaml
        ├── db/migration/
        │   ├── V1__create_api_keys.sql   Schema + pgcrypto
        │   └── V2__seed_api_keys.sql     Dev seed keys
        └── prompts/
            ├── system-openai.st
            ├── system-anthropic.st
            ├── system-ollama.st
            ├── system-default.st
            └── assistant-starter.st

observability/
├── prometheus.yml                       Prometheus scrape config (/llm/actuator/prometheus)
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
docker-compose.yml
PROMETHEUS_GRAFANA_SETUP.md               Prometheus + Grafana setup guide
```

---

## Insomnia Collection

Import `insomnia-collection.json` into Insomnia to get all endpoints pre-configured.

**Environment variables in the collection:**

| Variable          | Default                              | Description            |
|-------------------|--------------------------------------|------------------------|
| `base_url`        | `http://localhost:8080/llm`          | Gateway base URL       |
| `api_key`         | `llm-gateway-dev-key-2026`           | X-API-Key header value |
| `session_id`      | `test-session-001`                   | Chat session ID        |
| `openai_model`    | `gpt-4o`                             | OpenAI model           |
| `anthropic_model` | `claude-3-5-sonnet-20241022`         | Claude model           |
| `google_model`    | `gemini-1.5-pro-latest`              | Gemini model           |
| `cohere_model`    | `command-r-plus`                     | Cohere model           |
| `hf_model`        | `mistralai/Mistral-7B-Instruct-v0.1` | HuggingFace model      |

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
