# Insurance Logging System

A Java logging service for insurance systems. It records policy, claim, payment, underwriting, customer and security events as structured logs, **masks personal data automatically**, keeps searchable daily log files, and exposes **Prometheus metrics that Grafana turns into a live dashboard**.

Ships as a single runnable **`.jar`**: use it as a server that any app (Java, Node, Python, PHP...) sends events to over HTTP, or add it to a Java project as a library.

![Java](https://img.shields.io/badge/java-%23ED8B00.svg?style=for-the-badge&logo=openjdk&logoColor=white)
![Maven](https://img.shields.io/badge/Maven-C71A36?style=for-the-badge&logo=apachemaven&logoColor=white)
![Prometheus](https://img.shields.io/badge/Prometheus-E6522C?style=for-the-badge&logo=Prometheus&logoColor=white)
![Grafana](https://img.shields.io/badge/grafana-%23F46800.svg?style=for-the-badge&logo=grafana&logoColor=white)
![Docker](https://img.shields.io/badge/docker-%230db7ed.svg?style=for-the-badge&logo=docker&logoColor=white)

---

## Features

- **Insurance-specific events**: categories `POLICY`, `CLAIM`, `PAYMENT`, `UNDERWRITING`, `CUSTOMER`, `SECURITY`, `SYSTEM`, with policy number, claim ID, customer ID, product, amount, currency and channel (M-Pesa, card, bank, USSD...)
- **Personal data masking** before anything is stored, to support Kenya's Data Protection Act:
  - emails: `jane.doe@example.com` -> `j***@example.com`
  - phone numbers: `0712345678` -> `*******678`
  - card numbers keep only the last 4 digits
  - attributes like `nationalId`, `KRA PIN`, `password`, `otp` are replaced with `[REDACTED]`
- **Daily JSON-lines log files** (`logs/insurance-YYYY-MM-DD.log`) with automatic deletion after the retention period
- **Search API** by level, category, event type, policy, claim, customer and time range
- **Export** to CSV (safe against spreadsheet formula injection) or JSON for audits
- **Prometheus `/metrics`** with business metrics (events, claims by outcome, premiums and claim amounts) plus JVM and CPU metrics
- **Ready-made Grafana dashboard**, provisioned automatically with Docker Compose
- **Validation** with clear error messages; batches of up to 500 events per request
- Optional **API key** protection
- Built-in **traffic simulator** to see the dashboard working straight away

---

## Quick start

### Option 1: run the jar

Requires Java 17 or newer. Download `insurance-logging-system-1.0.0.jar` from the [Releases](../../releases) page, or build it:

```bash
mvn package                        # creates target/insurance-logging-system-1.0.0.jar
java -jar target/insurance-logging-system-1.0.0.jar
```

```
Insurance Logging System 1.0.0 running on http://localhost:8080
  Logs:    ./logs (kept 90 days)
  Metrics: http://localhost:8080/metrics
```

### Option 2: full stack with Grafana (Docker)

```bash
docker compose up --build
```

| Service | URL |
|---|---|
| Logging API | http://localhost:8080 |
| Prometheus | http://localhost:9090 |
| Grafana (admin / admin) | http://localhost:3000 |

Then send demo traffic and open Grafana; the **Insurance Logging System** dashboard is the home page:

```bash
java -jar target/insurance-logging-system-1.0.0.jar simulate --rate 10
```

---

## Sending events

```bash
curl -X POST http://localhost:8080/api/logs \
  -H "Content-Type: application/json" \
  -d '{
    "level": "INFO",
    "category": "CLAIM",
    "eventType": "CLAIM_SUBMITTED",
    "message": "Claim submitted by customer on 0712345678",
    "policyNumber": "POL-2024-778",
    "claimId": "CLM-1001",
    "customerId": "CUS-42",
    "product": "Medical",
    "amount": 45000,
    "channel": "mpesa",
    "attributes": { "region": "Nairobi", "nationalId": "12345678" }
  }'
```

Stored as:

```json
{
  "id": "3f0c...",
  "timestamp": "2026-11-25T08:15:30Z",
  "level": "INFO",
  "category": "CLAIM",
  "eventType": "CLAIM_SUBMITTED",
  "message": "Claim submitted by customer on *******678",
  "policyNumber": "POL-2024-778",
  "claimId": "CLM-1001",
  "amount": 45000,
  "currency": "KES",
  "channel": "mpesa",
  "attributes": { "region": "Nairobi", "nationalId": "[REDACTED]" }
}
```

Required fields: `category`, `eventType` (UPPER_SNAKE_CASE) and `message`. Send an array to log up to 500 events at once.

### Using it as a Java library

```java
InsuranceLogger log = InsuranceLogger.create(Path.of("logs"));

log.policyCreated("POL-1001", "CUS-42", "Medical", 3_500);
log.premiumPaid("POL-1001", 3_500, "mpesa");
log.claimSubmitted("CLM-5001", "POL-1001", 45_000, "mpesa");
log.claimDecision("CLM-5001", "POL-1001", true, 45_000, "assessed by J. Otieno");

log.log(LogEvent.builder(Category.UNDERWRITING, "RISK_ASSESSED")
        .policy("POL-1001").message("Risk score 0.32").build());

String prometheusText = log.metrics().scrape();
```

---

## API

| Method | Endpoint | Description |
|---|---|---|
| `POST` | `/api/logs` | Log one event or an array of events |
| `GET` | `/api/logs` | Search, newest first |
| `GET` | `/api/logs/export` | Download as `format=csv` (default) or `format=json` |
| `GET` | `/api/stats` | Counts by category and level |
| `GET` | `/metrics` | Prometheus metrics |
| `GET` | `/health` | Health check |

Search and export filters: `level` (minimum, e.g. `WARN`), `category`, `eventType`, `policyNumber`, `claimId`, `customerId`, `since`, `until` (ISO-8601) and `limit`.

```bash
curl "http://localhost:8080/api/logs?category=CLAIM&level=WARN&limit=20"
curl -OJ "http://localhost:8080/api/logs/export?format=csv&since=2026-11-01T00:00:00Z"
```

If `INSURELOG_API_KEY` is set, `/api/*` needs the header `X-API-Key: <key>`. `/metrics` and `/health` stay open for Prometheus and health checks.

---

## Grafana dashboard

`monitoring/grafana/dashboards/insurance-logging.json` contains:

- **Overview**: events per second, claims submitted, claim approval rate, premiums collected, errors, rejected events
- **Business activity**: events by category, claims by outcome, premiums by channel, claim amounts (median, 95th percentile, paid out)
- **Risk and errors**: payment failure rate, warnings and errors by level, security events such as failed logins
- **Service health**: ingest latency (p50/p95/p99), JVM heap, uptime, CPU

To use your own Grafana, add a Prometheus data source that scrapes `http://<host>:8080/metrics`, then import the dashboard JSON.

### Metrics

| Metric | Labels | Meaning |
|---|---|---|
| `insurelog_events_total` | category, event_type, level, channel | Events received |
| `insurelog_claims_total` | outcome (submitted, approved, rejected, paid) | Claims by outcome |
| `insurelog_amount_kes` | category, event_type, channel | Amounts in KES (count, sum, max, p50, p95) |
| `insurelog_rejected_events_total` | reason | Invalid events refused |
| `insurelog_ingest_seconds` | | Time to validate, mask and store events |
| `jvm_*`, `process_*`, `system_*` | | JVM memory, GC, threads, CPU, uptime |

---

## Configuration

| Option | Environment variable | Default |
|---|---|---|
| `--port` | `PORT` | `8080` |
| `--log-dir` | `LOG_DIR` | `./logs` |
| `--retention-days` | `RETENTION_DAYS` | `90` |
| `--memory-capacity` | `MEMORY_CAPACITY` | `50000` events kept in memory for search |
| `--api-key` | `INSURELOG_API_KEY` | not required |

```bash
java -jar insurance-logging-system-1.0.0.jar --help
```

---

## Project structure

```
src/main/java/com/felixkariuki/insurelog/
  App.java                  command line (serve, simulate)
  model/                    LogEvent, Level, Category, LogQuery
  core/                     InsuranceLogger, PiiMasker, EventValidator, LogStore
  metrics/                  InsuranceMetrics (Micrometer + Prometheus)
  http/                     ApiServer, CsvExporter
  sim/                      TrafficSimulator
monitoring/                 Prometheus config and Grafana provisioning + dashboard
```

## Tests

```bash
mvn test
```

Covers PII masking, validation, file storage and restart recovery, search filters, metrics, the HTTP API, API keys and CSV export.

## Author

**Felix Kariuki** - [@101feliki](https://github.com/101feliki)
