# InsureLog: Insurance Logging System

A **plug-and-play logging toolkit for insurance microservices**, built with **Java 17 and Spring Boot 3**.

Add one dependency to any Spring Boot service and it immediately gets structured insurance events, **automatic personal-data masking**, **correlation IDs that follow a customer across services**, business metrics in Actuator, and background shipping to a **central server** with search, export and a **Grafana dashboard**.

![Java](https://img.shields.io/badge/java_17-%23ED8B00.svg?style=for-the-badge&logo=openjdk&logoColor=white)
![Spring Boot](https://img.shields.io/badge/Spring_Boot_3-6DB33F?style=for-the-badge&logo=springboot&logoColor=white)
![Maven](https://img.shields.io/badge/Maven-C71A36?style=for-the-badge&logo=apachemaven&logoColor=white)
![Prometheus](https://img.shields.io/badge/Prometheus-E6522C?style=for-the-badge&logo=Prometheus&logoColor=white)
![Grafana](https://img.shields.io/badge/grafana-%23F46800.svg?style=for-the-badge&logo=grafana&logoColor=white)
![Docker](https://img.shields.io/badge/docker-%230db7ed.svg?style=for-the-badge&logo=docker&logoColor=white)

---

## Why

Insurance platforms are made of many moving parts: policy, underwriting, payments (M-Pesa, card, bank), claims, notifications, integrations. When a customer says "my claim payout never arrived", you need to follow that one journey through every service, without exposing their phone number or ID in the logs. InsureLog does that with almost no code in each service.

## Architecture

```
 ┌────────────────┐  ┌────────────────┐  ┌────────────────┐
 │ policy-service │  │ claims-service │  │payments-service│   any Spring Boot service
 │  + starter     │  │  + starter     │  │  + starter     │   (or anything that can POST JSON)
 └───────┬────────┘  └───────┬────────┘  └───────┬────────┘
         │  X-Correlation-Id passed along on every call   │
         └──────────── batched, async HTTP ───────────────┘
                              │
                     ┌────────▼─────────┐        ┌────────────┐     ┌─────────┐
                     │ insurelog-server │◀───────│ Prometheus │────▶│ Grafana │
                     │ search · export  │ scrape └────────────┘     └─────────┘
                     │ trace · Swagger  │
                     └──────────────────┘
```

| Module | What it is |
|---|---|
| `insurelog-core` | Framework-free library: event model, PII masking, validation, file storage, HTTP shipper, Micrometer metrics |
| `insurelog-spring-boot-starter` | **The plug-and-play part.** Auto-configures everything in any Spring Boot service |
| `insurelog-server` | Central Spring Boot service: ingest, search, trace a journey, CSV/JSON export, Swagger, Actuator |
| `demo-claims-service` | Example microservice using the starter, with optional live demo traffic |

---

## Plug it into a microservice (2 steps)

**1. Add the dependency**

```xml
<dependency>
  <groupId>com.felixkariuki</groupId>
  <artifactId>insurelog-spring-boot-starter</artifactId>
  <version>2.0.0</version>
</dependency>
```

**2. Point it at the server** (optional; without it, events go to the service's own log)

```yaml
spring:
  application:
    name: claims-service

insurelog:
  server:
    url: http://insurelog-server:8080
```

That's it. Now inject `InsuranceLogger` anywhere:

```java
@Service
public class ClaimService {

    private final InsuranceLogger insureLog;

    public ClaimService(InsuranceLogger insureLog) {
        this.insureLog = insureLog;
    }

    public void submit(Claim claim) {
        // ... business logic ...
        insureLog.claimSubmitted(claim.id(), claim.policyNumber(), claim.amount(), "mpesa");
    }

    public void approve(Claim claim) {
        insureLog.claimDecision(claim.id(), claim.policyNumber(), true, claim.amount(), "assessor J. Otieno");
    }
}
```

Or log any event with the builder:

```java
insureLog.log(LogEvent.builder(Category.UNDERWRITING, "RISK_ASSESSED")
        .policy("POL-1001")
        .customer("CUS-42")
        .message("Risk score 0.32 for customer on 0712345678")   // phone is masked automatically
        .attributes(Map.of("nationalId", "12345678"))             // becomes [REDACTED]
        .build());
```

### What the starter does automatically

| Feature | Details |
|---|---|
| **Service name** | Every event is stamped with `spring.application.name` |
| **Correlation IDs** | Reads `X-Correlation-Id` from incoming requests (or creates one), puts it in the MDC, returns it in the response, and **adds it to outgoing `RestTemplate` and `RestClient` calls**, so the next service logs under the same ID |
| **PII masking** | Phones (`0712345678` → `*******678`), emails (`j***@example.com`), card numbers (last 4 kept), and attributes like `nationalId`, `KRA PIN`, `password`, `otp` → `[REDACTED]` |
| **Never breaks your service** | Async, batched shipping with a bounded queue; if the server is down, events are retried and then dropped (and counted) rather than blocking requests. A failing sink can't throw into business code |
| **Metrics** | `insurelog_*` metrics in your service's own Actuator (`/actuator/prometheus`), plus shipper queue/sent/dropped |
| **Health** | `/actuator/health` shows an `insureLog` section with sent, dropped and queued counts |
| **Extensible** | Define any `EventSink` bean (Kafka, database, SIEM...) and it is added automatically |

### Configuration

```yaml
insurelog:
  enabled: true                 # switch everything off with false
  service-name: claims-service  # default: spring.application.name
  throw-on-invalid: false       # default: invalid events are skipped with a warning
  console:
    enabled: true               # JSON line per event in the app log (logger "insurelog")
  file:
    enabled: false              # local daily files as well
    directory: logs/insurelog
    retention-days: 30
  server:
    url: http://insurelog-server:8080
    api-key: ${INSURELOG_API_KEY:}
    batch-size: 100
    flush-interval: 2s
    queue-capacity: 10000
    max-retries: 3
  correlation:
    enabled: true
    header: X-Correlation-Id
    mdc-key: correlationId      # use %X{correlationId} in your log pattern
```

### Not using Spring?

Use `insurelog-core` directly from any Java app:

```java
InsuranceLogger log = InsuranceLogger.builder()
        .service("batch-renewals")
        .sink(new HttpShipper("http://insurelog-server:8080", null, 100, Duration.ofSeconds(2), 10_000, 3))
        .build();
```

Services in other languages (Node, Python, PHP...) can send JSON straight to `POST /api/logs`.

---

## The central server

```bash
java -jar insurelog-server-2.0.0.jar
```

| Method | Endpoint | Description |
|---|---|---|
| `POST` | `/api/logs` | One event or an array of up to 500 |
| `GET` | `/api/logs` | Search, newest first: `level` (minimum), `category`, `eventType`, `service`, `correlationId`, `policyNumber`, `claimId`, `customerId`, `since`, `until`, `limit` |
| `GET` | `/api/logs/trace/{correlationId}` | **One customer journey across every service, in order** |
| `GET` | `/api/logs/export` | Download `format=csv` (default) or `format=json` |
| `GET` | `/api/stats` | Counts by service, category and level |
| `GET` | `/swagger-ui.html` | Interactive API docs |
| `GET` | `/actuator/prometheus` | Metrics for Prometheus / Grafana |
| `GET` | `/actuator/health` | Health check |

Example trace:

```bash
curl http://localhost:8080/api/logs/trace/0ced-...-57eb
```
```
claims-service    CLAIM_SUBMITTED   CLM-7095   Claim submitted, customer phone *******678
claims-service    CLAIM_APPROVED    CLM-7095   Claim CLM-7095 approved: assessed automatically
payments-service  CLAIM_PAID        CLM-7095   Claim CLM-7095 paid out
```

Server settings (environment variables): `PORT` (8080), `LOG_DIR` (logs), `RETENTION_DAYS` (90), `MEMORY_CAPACITY` (50000), `INSURELOG_API_KEY` (if set, `/api/*` needs `X-API-Key`).

Demo traffic from several pretend services:

```bash
java -jar insurelog-server-2.0.0.jar simulate --rate 10
```

---

## Run everything with Grafana

```bash
docker compose up --build
```

| Service | URL |
|---|---|
| InsureLog server + Swagger | http://localhost:8080/swagger-ui.html |
| Demo claims service | http://localhost:8081 |
| Prometheus | http://localhost:9090 |
| Grafana (admin / admin) | http://localhost:3000 |

The demo claims service generates live traffic, and the **Insurance Logging System** dashboard opens as Grafana's home page:

- **Overview:** services reporting, events per second, claims submitted, approval rate, premiums collected, errors
- **Microservices:** events and errors per service (pick services with the dropdown)
- **Business activity:** events by category, claims by outcome, premiums by channel, claim amounts
- **Risk:** payment failure rate, security events, rejected events
- **Pipeline health:** shipper queue and drops in each service, server ingest latency, HTTP traffic, JVM heap and CPU per app

To add your own services, add them to `monitoring/prometheus/prometheus.yml`.

---

## Build

```bash
mvn install          # builds and tests every module
```

| Jar | Use |
|---|---|
| `insurelog-server/target/insurelog-server-2.0.0.jar` | Run the central server |
| `insurelog-spring-boot-starter/target/insurelog-spring-boot-starter-2.0.0.jar` | Dependency for your microservices |
| `insurelog-core/target/insurelog-core-2.0.0.jar` | Library for non-Spring Java apps |
| `demo-claims-service/target/demo-claims-service-2.0.0.jar` | Example microservice |

**Using the starter from GitHub without publishing to Maven Central:** create a GitHub release (for example `v2.0.0`) and add [JitPack](https://jitpack.io):

```xml
<repositories>
  <repository>
    <id>jitpack.io</id>
    <url>https://jitpack.io</url>
  </repository>
</repositories>

<dependency>
  <groupId>com.github.101feliki.java-logging-system</groupId>
  <artifactId>insurelog-spring-boot-starter</artifactId>
  <version>v2.0.0</version>
</dependency>
```

## Tests

26 tests cover PII masking, validation, storage and restart recovery, non-blocking shipping and shutdown flushing, Spring auto-configuration, **correlation IDs passed between services over HTTP**, the server API, API keys, CSV export and the demo service's full claim journey.

## Author

**Felix Kariuki** - [@101feliki](https://github.com/101feliki)
