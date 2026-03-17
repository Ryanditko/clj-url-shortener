# Clojure URL Shortener

![Paper URL Shortener](https://i.imgur.com/ifyjyTh.jpeg)

<div align="center">

![Clojure](https://img.shields.io/badge/Clojure-91DC47?style=for-the-badge&logo=clojure&logoColor=5881D8)
![Datomic](https://img.shields.io/badge/Datomic-FF3621?style=for-the-badge&logo=databricks&logoColor=white)
![Apache Kafka](https://img.shields.io/badge/Apache_Kafka-231F20?style=for-the-badge&logo=apachekafka&logoColor=white)
![Pedestal](https://img.shields.io/badge/Pedestal-5881D8?style=for-the-badge&logo=clojure&logoColor=white)
![Redis](https://img.shields.io/badge/Redis-DC382D?style=for-the-badge&logo=redis&logoColor=white)
![Docker](https://img.shields.io/badge/Docker-2496ED?style=for-the-badge&logo=docker&logoColor=white)

![Status](https://img.shields.io/badge/Status-Active-91DC47?style=flat-square&logo=checkmarx&logoColor=white)
![License](https://img.shields.io/badge/License-Academic-820AD1?style=flat-square&logo=bookstack&logoColor=white)
![Tests](https://img.shields.io/badge/Tests-39_passing-91DC47?style=flat-square&logo=testcafe&logoColor=white)

</div>

A production-grade URL shortener written in Clojure, following the Diplomat Architecture pattern. Uses Datomic for immutable URL storage and time-travel analytics, Redis for high-performance caching, and Apache Kafka for real-time click event streaming. Exposes a RESTful API via Pedestal with support for custom short codes, URL expiration and click statistics.

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
| ![Docker](https://img.shields.io/badge/-2496ED?style=flat-square&logo=docker&logoColor=white) | Containerization | Docker Compose |
| ![Testing](https://img.shields.io/badge/-1B1B1B?style=flat-square&logo=testcafe&logoColor=white) | Testing | clojure.test |

---

## Architecture

Follows the **Diplomat Architecture** (Hexagonal Architecture variant), strictly separating domain logic from infrastructure.

![Architecture Diagram](https://i.imgur.com/8nf5PzT.jpeg)

See [ARCHITECTURE.md](./ARCHITECTURE.md) for the full specification.

### Project Structure

```
src/url_shortener/
├── models/url.clj                    # Domain models (Url, UrlStats, ClickEvent)
├── logic/shortener.clj               # Pure business logic (Base62, validation, stats)
├── controllers/url.clj               # Use case orchestration (logic sandwich)
├── adapters/url.clj                  # Data transformations (wire <-> model)
├── wire/
│   ├── in/create_url_request.clj     # Inbound schemas (loose)
│   ├── out/url_response.clj          # Outbound API schemas (strict)
│   ├── out/kafka_event.clj           # Kafka event schemas (strict)
│   ├── cache/url_cache.clj           # Redis cache schemas (strict)
│   └── datomic/url.clj              # Datomic persistence schemas (strict)
├── diplomat/
│   ├── http_server.clj               # Pedestal routes and handlers
│   ├── datomic.clj                   # Datomic operations + Component lifecycle
│   ├── datomic/schema.clj            # Datomic attribute definitions
│   ├── datomic/migrate.clj           # Schema migration entry point
│   ├── cache.clj                     # Redis caching (fault-tolerant)
│   └── producer.clj                  # Kafka event publishing (fault-tolerant)
├── system.clj                        # Component system map (DI)
└── core.clj                          # Application entry point
```

### Data Flow

```
HTTP Request → wire.in → adapters → model → logic → controllers → adapters → wire.out → HTTP Response
                                                         ↓
                                                   diplomat.datomic (persist)
                                                   diplomat.cache (cache)
                                                   diplomat.producer (publish event)
```

---

## Prerequisites

- **Java** 11+
- **Leiningen** 2.9+
- **Docker & Docker Compose** (for Redis and Kafka in local development)

---

## Getting Started

### 1. Clone the repository

```bash
git clone https://github.com/Ryanditko/clj-url-shortener.git
cd clj-url-shortener
```

### 2. Install dependencies

```bash
lein deps
```

### 3. Start infrastructure services

```bash
docker-compose up -d
```

This starts Redis (port 6379), Zookeeper (port 2181), and Kafka (port 9092).

### 4. Run the application

```bash
lein run
```

The API starts on `http://localhost:8080`.

### 5. Run database migrations

```bash
lein migrate
```

---

## API Endpoints

| Method   | Endpoint                  | Description              |
|----------|---------------------------|--------------------------|
| `GET`    | `/health`                 | Health check             |
| `POST`   | `/api/urls`               | Shorten a URL            |
| `GET`    | `/r/:code`                | Redirect to original URL |
| `GET`    | `/api/urls/:code/stats`   | Get click statistics     |
| `DELETE` | `/api/urls/:code`         | Deactivate a short URL   |

### Create a short URL

```bash
curl -X POST http://localhost:8080/api/urls \
  -H "Content-Type: application/json" \
  -d '{"original-url": "https://example.com/very/long/url", "owner": "user@example.com"}'
```

**Response** (201):
```json
{
  "id": "550e8400-e29b-41d4-a716-446655440000",
  "original-url": "https://example.com/very/long/url",
  "short-code": "A1b2C3d4",
  "short-url": "http://localhost:8080/r/A1b2C3d4",
  "clicks": 0,
  "owner": "user@example.com",
  "created-at": "2026-03-17T15:30:00.000Z"
}
```

### Redirect

```bash
curl -L http://localhost:8080/r/A1b2C3d4
```

### Get statistics

```bash
curl http://localhost:8080/api/urls/A1b2C3d4/stats
```

**Response** (200):
```json
{
  "short-code": "A1b2C3d4",
  "original-url": "https://example.com/very/long/url",
  "total-clicks": 42,
  "last-clicked-at": "2026-03-17T16:00:00.000Z",
  "created-at": "2026-03-17T15:30:00.000Z",
  "active": true
}
```

### Deactivate

```bash
curl -X DELETE http://localhost:8080/api/urls/A1b2C3d4
```

---

## Testing

All tests run self-contained with in-memory Datomic and NoOp mocks for Redis and Kafka.

```bash
# Run all tests
lein test

# Unit tests only (fast)
lein test-unit

# Integration tests only
lein test-integration

# Coverage report
lein coverage
```

| Category | Tests | Assertions |
|----------|-------|------------|
| Logic | 13 | 65 |
| Adapters | 10 | 49 |
| Controllers | 3 | 17 |
| Datomic | 7 | 19 |
| Integration | 6 | 32 |
| **Total** | **39** | **182** |

See [TESTING.md](./TESTING.md) for the full testing guide.

---

## Configuration

Application configuration lives in `resources/config.edn` and supports environment-specific profiles:

| Key | Description | Default |
|-----|-------------|---------|
| `:datomic-uri` | Datomic connection URI | `datomic:mem://url-shortener` |
| `:redis-host` | Redis host | `localhost` |
| `:redis-port` | Redis port | `6379` |
| `:kafka-bootstrap-servers` | Kafka broker address | `localhost:9092` |
| `:http-port` | HTTP server port | `8080` |

---

## Fault Tolerance

The service is designed to gracefully degrade when external dependencies are unavailable:

- **Redis unavailable**: Cache operations are skipped, all reads fall through to Datomic. A warning is logged.
- **Kafka unavailable**: Events are silently dropped. URL operations continue normally. A warning is logged.
- **Datomic**: Required for core operations. The service will not start without a valid Datomic connection.

---

## Documentation

- [ARCHITECTURE.md](./ARCHITECTURE.md) - Diplomat Architecture specification and layer rules.
- [TESTING.md](./TESTING.md) - Testing guide, patterns and statistics.
