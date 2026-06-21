# Prometheus + Grafana Setup — LLM Gateway

This guide explains how metrics flow from the LLM Gateway into Prometheus and how to
visualise them in Grafana, including the importable dashboard shipped in this repo.

```
┌────────────┐   scrape /llm/v1/actuator/prometheus   ┌────────────┐   PromQL    ┌──────────┐
│ LLM Gateway│ ──────────────────────────────────► │ Prometheus │ ──────────► │ Grafana  │
│  :8080     │        every 10s                    │   :9090    │             │  :3000   │
└────────────┘                                     └────────────┘             └──────────┘
```

---

## 1. What the app exposes

The gateway uses **Spring Boot Actuator + Micrometer** with the Prometheus registry
(`micrometer-registry-prometheus`). The following endpoints are exposed (see
`application.yaml` → `management.endpoints.web.exposure.include`):

| Endpoint                         | Purpose                                              |
|----------------------------------|------------------------------------------------------|
| `/llm/v1/actuator/health`           | Liveness/readiness + component health (redis, r2dbc) |
| `/llm/v1/actuator/metrics`          | Browse individual metrics (JSON)                     |
| `/llm/v1/actuator/prometheus`       | **Prometheus scrape endpoint** (text exposition)     |
| `/llm/v1/actuator/circuitbreakers`  | Resilience4j circuit-breaker state                   |

> **Important — the `/llm/v1` prefix.** The app sets `spring.webflux.base-path: /llm/v1`,
> so the actuator endpoints live under `/llm/v1/actuator/...`, **not** `/actuator/...`.
> Prometheus must scrape `/llm/v1/actuator/prometheus` (already configured in
> `observability/prometheus.yml`).

Quick check once the app is running:

```bash
curl -s http://localhost:8080/llm/v1/actuator/prometheus | grep llm_provider_calls_total
```

### Histogram buckets (latency percentiles)

`application.yaml` enables Prometheus histogram buckets so Grafana can compute
p50/p95/p99 latency:

```yaml
management:
  metrics:
    distribution:
      percentiles-histogram:
        http.server.requests: true      # REST API turnaround time
        llm.gateway.execution: true     # @Timed gateway turnaround
        llm.request.latency.seconds: true  # per-provider LLM call latency
```

---

## 2. Custom metrics reference

These are emitted by `LlmMetricsService` and the `@Timed` annotation on the gateway
facade. Use them to see **how many LLM calls go to each provider**, token usage, and
turnaround time.

| Metric (Prometheus name)              | Type       | Key labels                       | Meaning |
|---------------------------------------|------------|----------------------------------|---------|
| `llm_provider_calls_total`            | counter    | `provider`, `model`, `outcome`   | **Calls routed to each provider** (success/error) |
| `llm_requests_total`                  | counter    | `provider`, `cache_hit`          | Total requests, incl. cache hits |
| `llm_requests_errors_total`           | counter    | `provider`, `error_type`         | Errors by type |
| `llm_requests_rejected_total`         | counter    | `provider`, `reason`             | Requests blocked by guardrails |
| `llm_request_latency_seconds`         | histogram  | `provider`                       | Per-provider LLM call latency |
| `llm_tokens_total`                    | counter    | `provider`, `model`, `type`      | Token usage (prompt/completion/total) |
| `llm_prompt_length_chars`             | summary    | `provider`                       | Prompt size distribution |
| `llm_gateway_execution_seconds`       | timer (`@Timed`) | `operation`                | **Gateway turnaround time** (execute / failover / auto-failover) |
| `http_server_requests_seconds`        | timer (built-in) | `uri`, `method`, `status`  | **REST API turnaround time per endpoint** |

Example PromQL:

```promql
# Calls per provider over the last 5 minutes
sum by (provider) (rate(llm_provider_calls_total[5m]))

# REST API turnaround p95 per endpoint
histogram_quantile(0.95,
  sum by (le, uri) (rate(http_server_requests_seconds_bucket{uri!~"/actuator.*"}[5m])))

# Token usage rate per provider
sum by (provider) (rate(llm_tokens_total{type="total"}[5m]))
```

---

## 3. Run the stack

Everything (Postgres, Redis, Prometheus, Grafana, Tempo, Loki) is defined in
`docker-compose.yml`.

```bash
# 1. Start the observability + data stack
docker compose up -d prometheus grafana postgres redis

# 2. Start the gateway (from the repo root)
mvn spring-boot:run
#   ...or run LLMGatewayApplication from your IDE
```

The app listens on `:8080`. Prometheus scrapes it via `host.docker.internal:8080`
(works on Docker Desktop for Mac/Windows; on Linux add
`extra_hosts: ["host.docker.internal:host-gateway"]` to the prometheus service).

---

## 4. Prometheus

Config: `observability/prometheus.yml`

```yaml
scrape_configs:
  - job_name: 'llm-gateway'
    metrics_path: /llm/v1/actuator/prometheus
    static_configs:
      - targets: ['host.docker.internal:8080']
    scrape_interval: 10s
```

Verify the target is **UP**:

- Open <http://localhost:9090/targets> → the `llm-gateway` job should be green.
- Query a metric: <http://localhost:9090/graph> → `llm_provider_calls_total`.

If you change `prometheus.yml`, reload without restarting:

```bash
curl -X POST http://localhost:9090/-/reload
```

---

## 5. Grafana

URL: <http://localhost:3000>  •  default login: `admin` / `admin`

### Data source (auto-provisioned)

`observability/grafana/provisioning/datasources/datasources.yml` already wires up the
Prometheus data source (uid `prometheus`), plus Tempo and Loki.

### Dashboard

The dashboard is **auto-provisioned**. On startup Grafana loads every JSON under
`observability/grafana/dashboards/` (mounted to `/etc/dashboards`), so the
**“LLM Gateway — Observability”** dashboard appears under
*Dashboards → LLM Gateway* with no manual step.

Provisioning config: `observability/grafana/provisioning/dashboards/dashboards.yml`.

### Manual import (alternative)

If you prefer importing by hand (e.g. into a different Grafana):

1. Grafana → **Dashboards → New → Import**.
2. **Upload JSON file** → choose
   `observability/grafana/dashboards/grafana-dashboard-llm-gateway.json`.
3. Select your Prometheus data source when prompted → **Import**.

### What the dashboard shows

- **Overview** — total calls, call rate, error rate, cache-hit ratio, total tokens, rejections.
- **Per-Provider Routing** — calls per provider, distribution donut, success/error
  outcomes, total per provider (answers *“how many LLM calls go to each provider”*).
- **Latency & REST API Turnaround** — p95/avg turnaround **per REST endpoint**,
  `@Timed` gateway execution time, per-provider call latency.
- **Tokens & Cost** — token usage rate and totals by provider/type.
- **Errors & Resilience** — errors by type, circuit-breaker state.
- **JVM & System** — memory, CPU, threads.

Use the **Provider** and **Application** template variables at the top to filter.

---

## 6. Troubleshooting

| Symptom | Cause / Fix |
|---------|-------------|
| Prometheus target DOWN | Wrong path — must be `/llm/v1/actuator/prometheus`. Confirm the app is on `:8080`. |
| Target DOWN on Linux | `host.docker.internal` not resolvable — add `extra_hosts: ["host.docker.internal:host-gateway"]` to the prometheus service. |
| No `llm_*` metrics | They register lazily on first use — send a request to `POST /llm/v1/query` first. |
| p95 panels empty | Histogram buckets need `percentiles-histogram` (already set) **and** at least one request to populate buckets. |
| Dashboard shows “No data” | Check the **Application** variable equals `llm-gateway` and the time range covers recent traffic. |
| Grafana dashboard missing | Ensure `./observability/grafana/dashboards` is mounted to `/etc/dashboards` (see `docker-compose.yml`). |
