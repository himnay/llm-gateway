# LLM Gateway

A production-ready, reactive Spring Boot gateway that provides a single unified API for multiple LLM providers (OpenAI, Anthropic Claude, Ollama). Handles routing, failover, multi-turn chat memory, prompt safety, guardrails, caching, API-key authentication, and full observability out of the box.

---

## Table of Contents

- [Architecture Overview](#architecture-overview)
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
│                      ├─ 1. Prompt Sanitization                  │
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
│    PostgreSQL  ──  API key registry (Flyway managed)            │
│    Redis       ──  Prompt cache + Chat memory                   │
│    Prometheus  ──  Metrics scraping                             │
│    OTLP        ──  Distributed traces → Grafana Tempo           │
└─────────────────────────────────────────────────────────────────┘
```

---

## Tech Stack

| Layer           | Technology                                          |
|-----------------|-----------------------------------------------------|
| Runtime         | Java 21, Spring Boot 4.0.6                          |
| Web             | Spring WebFlux (reactive, non-blocking)             |
| Security        | Spring Security WebFlux + PostgreSQL API key table  |
| LLM Integration | Spring AI 2.0.0-M8                                  |
| LLM Providers   | OpenAI, Anthropic Claude, Ollama                    |
| Cache + Memory  | Redis (Spring Data Redis / Lettuce)                 |
| Database        | PostgreSQL 16 (R2DBC reactive + JDBC for Flyway)    |
| Migrations      | Flyway                                              |
| Resilience      | Resilience4j (Circuit Breaker, Retry, Rate Limiter) |
| Observability   | Micrometer + OTEL, Prometheus, Grafana Tempo        |
| Build           | Maven 3.9+                                          |

---

## Features

- **Multi-provider routing** — single API, routed to OpenAI, Anthropic, or Ollama
- **Failover chain** — tries the next provider automatically on failure
- **Multi-turn chat** — Redis-backed conversation memory per `session_id`
- **Session management** — `DELETE /sessions/{sessionId}` clears history
- **Prompt template system** — per-provider `.st` files with variable substitution
- **Assistant message prefill** — steer response format without consuming a user turn
- **API key authentication** — Spring Security WebFlux backed by a PostgreSQL table; SHA-256 hashed keys, expiry support, `last_used` tracking
- **9-step guardrail chain** — prompt safety, PII redaction, toxicity filter, topic restriction, hallucination monitoring, response format validation
- **Redis prompt cache** — SHA-256 keyed (includes system prompt, template vars, assistant message), configurable TTL
- **Streaming** — SSE token streaming per provider
- **Structured output** — typed Java record extraction from LLM responses (via facade for full observability)
- **Request timeout** — configurable per-request timeout with 504 response
- **X-Request-ID propagation** — correlation ID accepted from callers and echoed in response headers and body
- **Real health check** — `/health` probes Redis connectivity
- **Provider enable/disable** — `@ConditionalOnProperty` per service; disabled providers never start
- **Distributed tracing** — OTEL spans with trace/span IDs in every log line
- **Prometheus metrics** — request counts, latencies, cache hits, rejections, errors

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
docker compose up -d postgres redis
```

Flyway runs automatically on startup and creates the `api_keys` table with two seed keys:

| Key name        | Raw value (local dev only)   |
|-----------------|------------------------------|
| Development Key | `llm-gateway-dev-key-2026`   |
| Admin Key       | `llm-gateway-admin-key-2026` |

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

`compose.yaml` includes all infrastructure services:

```bash
# Start everything
docker compose up -d

# Start only infrastructure (run the gateway from IDE/Maven)
docker compose up -d postgres redis

# Observability stack
docker compose up -d prometheus grafana tempo
```

| Service       | Port | Purpose                    |
|---------------|------|----------------------------|
| PostgreSQL    | 5432 | API key registry           |
| Redis         | 6379 | Prompt cache + chat memory |
| Prometheus    | 9090 | Metrics                    |
| Grafana       | 3000 | Dashboards (admin/admin)   |
| Grafana Tempo | 4318 | OTLP trace collector       |

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
| Database | `POSTGRES_DB`       | `llmgateway` |
| Username | `POSTGRES_USER`     | `llmgateway` |
| Password | `POSTGRES_PASSWORD` | `secret`     |

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

### Security

| Env Var                    | Default | Description                   |
|----------------------------|---------|-------------------------------|
| `GATEWAY_AUTH_ENABLED`     | `false` | Set `true` in production      |
| `LLM_SANITIZATION_ENABLED` | `true`  | Prompt injection detection    |
| `LLM_MAX_PROMPT_LENGTH`    | `10000` | Hard cap on prompt characters |

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

---

## Observability

### Prometheus metrics

Exposed at `GET /actuator/prometheus`.

| Metric                  | Labels                   |
|-------------------------|--------------------------|
| `llm.requests.total`    | `provider`, `cache_hit`  |
| `llm.request.latency`   | `provider`               |
| `llm.prompt.length`     | `provider`               |
| `llm.cache.hits`        | `provider`               |
| `llm.errors`            | `provider`, `error_type` |
| `llm.rejected.requests` | `provider`, `reason`     |

### Distributed Tracing

Every request creates an OTEL span `llm.request`. Trace and span IDs appear in every log line via MDC. Structured extraction creates a separate `llm.structured` span.

### Actuator endpoints

| Endpoint                    | Description                               |
|-----------------------------|-------------------------------------------|
| `/actuator/health`          | Spring Boot health (includes Redis probe) |
| `/actuator/prometheus`      | Prometheus scrape                         |
| `/actuator/circuitbreakers` | Resilience4j state                        |
| `/actuator/loggers`         | Runtime log level changes                 |

---

## Project Structure

```
src/
└── main/
    ├── java/com/llm/gateway/llm_gateway/
    │   ├── cache/         PromptCacheService, RedisChatMemoryRepository
    │   ├── config/        ChatClientConfig, LlmRouterConfig, ObservabilityConfig, ...
    │   ├── dto/           LlmRequest, LlmResponse, LlmProvider, StructuredLlmResponse
    │   ├── exception/     GlobalExceptionHandler, LLMProviderNotSupportedException
    │   ├── facade/        LlmGatewayFacade (execute, executeWithFailover, executeStructured)
    │   ├── guardrail/     9 advisor classes + AdvisorUtils (shared extraction helpers)
    │   ├── handler/       LlmHandler, LlmStreamHandler
    │   ├── observability/ LlmMetricsService
    │   ├── security/      SecurityConfig, ApiKeyService, PromptSanitizer, ...
    │   ├── service/       OpenAiService, AnthropicClaudeService, OllamaService, ...
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

docker/
├── prometheus.yml
└── tempo.yaml
compose.yaml
```

---

## Building

```bash
mvn clean install          # compile + test
mvn spring-boot:run        # run locally
mvn clean package -DskipTests && java -jar target/llm-gateway-*.jar
```
