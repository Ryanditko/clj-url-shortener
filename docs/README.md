# Clojure URL Shortener

<div align="center">

![Clojure](https://img.shields.io/badge/Clojure-91DC47?style=for-the-badge&logo=clojure&logoColor=5881D8)
![Datomic](https://img.shields.io/badge/Datomic-FF3621?style=for-the-badge&logo=databricks&logoColor=white)
![Apache Kafka](https://img.shields.io/badge/Apache_Kafka-231F20?style=for-the-badge&logo=apachekafka&logoColor=white)
![Pedestal](https://img.shields.io/badge/Pedestal-5881D8?style=for-the-badge&logo=clojure&logoColor=white)
![Redis](https://img.shields.io/badge/Redis-DC382D?style=for-the-badge&logo=redis&logoColor=white)
![Docker](https://img.shields.io/badge/Docker-2496ED?style=for-the-badge&logo=docker&logoColor=white)

![Status](https://img.shields.io/badge/Status-Active-91DC47?style=flat-square&logo=checkmarx&logoColor=white)
![License](https://img.shields.io/badge/License-Academic-820AD1?style=flat-square&logo=bookstack&logoColor=white)
![Tests](https://img.shields.io/badge/Tests-56_passing-91DC47?style=flat-square&logo=testcafe&logoColor=white)

</div>

A production-grade URL shortener written in Clojure, following the Diplomat Architecture pattern. Uses Datomic for immutable URL storage, Redis for high-performance caching, and Apache Kafka for real-time click event streaming and analytics aggregation. Includes JWT authentication, per-IP rate limiting, Prometheus metrics, paginated statistics, and a full CI/CD pipeline via GitHub Actions.

---

## System Overview

```mermaid
graph TB
    Client([Client]) -->|HTTP| LB[Load Balancer]
    LB --> Pedestal[Pedestal HTTP Server]

    subgraph interceptors [Interceptor Chain]
        RateLimit[Rate Limiter]
        Metrics[Metrics Collector]
        Auth[JWT Auth]
        ErrorHandler[Error Handler]
    end

    Pedestal --> interceptors
    interceptors --> Handlers[Route Handlers]
    Handlers --> Controllers[Controllers]
    Controllers --> Logic[Logic - Pure Functions]
    Controllers --> DatomicDB[(Datomic)]
    Controllers --> RedisCache[(Redis Cache)]
    Controllers --> KafkaProducer[Kafka Producer]
    KafkaProducer --> KafkaBroker[Kafka Broker]
    KafkaBroker --> KafkaConsumer[Kafka Consumer]
    KafkaConsumer -->|analytics aggregation| DatomicDB
    Pedestal -->|"/metrics"| Prometheus[Prometheus Scraper]
```

---

## Stack

| Icon | Concern | Technology |
| --- | --- | --- |
| ![Clojure](https://img.shields.io/badge/-91DC47?style=flat-square&logo=clojure&logoColor=5881D8) | Language | Clojure |
| ![Pedestal](https://img.shields.io/badge/-5881D8?style=flat-square&logo=clojure&logoColor=white) | HTTP Server / API Gateway | Pedestal + Ring |
| ![Datomic](https://img.shields.io/badge/-FF3621?style=flat-square&logo=databricks&logoColor=white) | Database / URL Storage | Datomic |
| ![Redis](https://img.shields.io/badge/-DC382D?style=flat-square&logo=redis&logoColor=white) | Caching | Redis |
| ![Kafka](https://img.shields.io/badge/-231F20?style=flat-square&logo=apachekafka&logoColor=white) | Event Streaming | Apache Kafka |
| ![Schema](https://img.shields.io/badge/-820AD1?style=flat-square&logo=clojure&logoColor=white) | Schema Validation | Plumatic Schema |
| ![Prometheus](https://img.shields.io/badge/-E6522C?style=flat-square&logo=prometheus&logoColor=white) | Observability | Prometheus Metrics |
| ![Docker](https://img.shields.io/badge/-2496ED?style=flat-square&logo=docker&logoColor=white) | Containerization | Docker Compose |
| ![Testing](https://img.shields.io/badge/-1B1B1B?style=flat-square&logo=testcafe&logoColor=white) | Testing | clojure.test |
| ![CI](https://img.shields.io/badge/-2088FF?style=flat-square&logo=githubactions&logoColor=white) | CI/CD | GitHub Actions |

---

## Architecture

Follows the **Diplomat Architecture** (Hexagonal Architecture variant), strictly separating domain logic from infrastructure. Each layer has a single responsibility and well-defined access rules.

```mermaid
graph LR
    subgraph external [External World]
        HTTPClient([HTTP Clients])
        KafkaBrokerExt([Kafka Broker])
    end

    subgraph boundary [Boundary Layer]
        Diplomats[Diplomats]
        Wire[Wire Schemas]
        Adapters[Adapters]
    end

    subgraph domain [Domain Core]
        Controllers[Controllers]
        LogicLayer[Logic]
        Models[Models]
    end

    external --> boundary
    boundary --> domain
    Diplomats --> Wire
    Diplomats --> Adapters
    Adapters --> Wire
    Adapters --> Models
    Controllers --> LogicLayer
    Controllers --> Models
    LogicLayer --> Models
```

- **Models** - Pure domain entities (`Url`, `UrlStats`, `ClickEvent`) defined with strict Prismatic Schemas. No dependencies on any other layer.
- **Logic** - Pure business rules without side effects: Base62 encoding, URL validation, expiration calculation, click counting, statistics aggregation, JWT token management, and rate limiting.
- **Controllers** - Use case orchestration following the logic sandwich pattern: consume data from diplomats, compute with pure logic, produce side effects through diplomats.
- **Adapters** - Pure transformation functions between wire schemas and domain models. Inbound adapters convert loose external data into strict internal models; outbound adapters do the reverse.
- **Wire** - External data contracts. `wire.in` uses loose schemas (tolerant reader), while `wire.out`, `wire.cache` and `wire.datomic` use strict schemas (conservative writer).
- **Diplomats** - All external communication: HTTP server (Pedestal), database (Datomic), cache (Redis), event streaming (Kafka producer and consumer). Each diplomat is fault-tolerant and manages its own Component lifecycle.

See [ARCHITECTURE.md](./ARCHITECTURE.md) for the full specification with layer access rules.

---

### Data Flow

```mermaid
sequenceDiagram
    participant C as Client
    participant P as Pedestal
    participant Auth as JWT Auth
    participant Ctrl as Controller
    participant D as Datomic
    participant R as Redis
    participant K as Kafka

    C->>P: POST /api/urls (with Bearer token)
    P->>Auth: Validate JWT
    Auth->>P: Identity injected
    P->>Ctrl: create-url!
    Ctrl->>D: save-url!
    Ctrl->>R: cache-url!
    Ctrl->>K: publish url.created
    P->>C: 201 Created

    C->>P: GET /r/:code
    P->>Ctrl: redirect-url!
    Ctrl->>R: get-cached-url
    R-->>Ctrl: cache hit / miss
    Ctrl->>D: find-url (on miss)
    P->>C: 302 Redirect
    Ctrl-->>K: publish url.accessed (async)
    K-->>D: Consumer aggregates analytics
```

---

## API

| Method   | Endpoint                       | Auth     | Description                    |
|----------|--------------------------------|----------|--------------------------------|
| `GET`    | `/health`                      | Public   | Health check                   |
| `GET`    | `/metrics`                     | Public   | Prometheus metrics             |
| `POST`   | `/api/auth/login`             | Public   | Authenticate and get JWT token |
| `POST`   | `/api/urls`                   | Required | Shorten a URL                  |
| `GET`    | `/r/:code`                     | Public   | Redirect to original URL       |
| `GET`    | `/api/urls/:code/stats`       | Required | Get click statistics (paginated) |
| `GET`    | `/api/urls/:code/analytics`   | Required | Get daily analytics breakdown  |
| `DELETE` | `/api/urls/:code`             | Required | Deactivate a short URL         |

---

## Security

- **JWT Authentication** - Protected endpoints require a `Bearer` token obtained via `/api/auth/login`.
- **Rate Limiting** - Per-IP token bucket: 30 req/min for API, 100 req/min for redirects, 5 req/min for login (brute force protection).
- **429 Too Many Requests** - Includes `Retry-After` header when rate limit is exceeded.

---

## Observability

- **`/metrics`** endpoint exposes Prometheus-compatible metrics.
- `http_requests_total` - Request count by method, path, and status.
- `http_request_duration_seconds` - Request latency histogram.
- `urlshortener_urls_created_total` - Business metric for URL creation.
- `urlshortener_redirects_total` - Business metric for redirects.
- `urlshortener_cache_hits_total` / `urlshortener_cache_misses_total` - Cache effectiveness.
- JVM metrics (GC, memory, threads) via Prometheus JVM instrumentation.

---

## Fault Tolerance

The service is designed to gracefully degrade when external dependencies are unavailable:

- **Redis unavailable** - Cache operations are skipped, all reads fall through to Datomic.
- **Kafka unavailable** - Events are silently dropped. URL operations continue normally. Analytics will not be updated.
- **Datomic** - Required for core operations. The service will not start without a valid connection.

---

## CI/CD

GitHub Actions pipeline runs on every push and PR to `main`:

- **Lint** - clj-kondo static analysis.
- **Test** - `lein test` on Java 11 and Java 17 matrix.
- **Coverage** - `lein coverage` report uploaded as artifact.

---

## Documentation

| Document | Description |
|----------|-------------|
| [ARCHITECTURE.md](./ARCHITECTURE.md) | Diplomat Architecture specification and layer access rules |
| [TESTING.md](./TESTING.md) | Testing guide, patterns and statistics (56 tests, 281 assertions) |
| [SETUP.md](./SETUP.md) | Prerequisites, getting started, API usage and configuration |
