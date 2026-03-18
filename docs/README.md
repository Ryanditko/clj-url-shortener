# Clojure URL Shortener

![System Overview](./assets/system-overview.png)

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

Follows the **Diplomat Architecture** (Hexagonal Architecture variant), strictly separating domain logic from infrastructure. Each layer has a single responsibility and well-defined access rules.

![Diplomat Architecture](./assets/diplomat-architecture.png)

- **Models** - Pure domain entities (`Url`, `UrlStats`, `ClickEvent`) defined with strict Prismatic Schemas. No dependencies on any other layer.
- **Logic** - Pure business rules without side effects: Base62 encoding, URL validation, expiration calculation, click counting and statistics aggregation.
- **Controllers** - Use case orchestration following the logic sandwich pattern: consume data from diplomats, compute with pure logic, produce side effects through diplomats.
- **Adapters** - Pure transformation functions between wire schemas and domain models. Inbound adapters convert loose external data into strict internal models; outbound adapters do the reverse.
- **Wire** - External data contracts. `wire.in` uses loose schemas (tolerant reader), while `wire.out`, `wire.cache` and `wire.datomic` use strict schemas (conservative writer).
- **Diplomats** - All external communication: HTTP server (Pedestal), database (Datomic), cache (Redis) and event streaming (Kafka). Each diplomat is fault-tolerant and manages its own Component lifecycle.

See [ARCHITECTURE.md](./ARCHITECTURE.md) for the full specification with layer access rules.

---

### Project Structure

![Project Structure](./assets/project-structure.png)

---

### Data Flow

![Data Flow](./assets/data-flow.png)

---

## API

| Method   | Endpoint                  | Description              |
|----------|---------------------------|--------------------------|
| `GET`    | `/health`                 | Health check             |
| `POST`   | `/api/urls`               | Shorten a URL            |
| `GET`    | `/r/:code`                | Redirect to original URL |
| `GET`    | `/api/urls/:code/stats`   | Get click statistics     |
| `DELETE` | `/api/urls/:code`         | Deactivate a short URL   |

---

## Fault Tolerance

The service is designed to gracefully degrade when external dependencies are unavailable:

- **Redis unavailable** - Cache operations are skipped, all reads fall through to Datomic.
- **Kafka unavailable** - Events are silently dropped. URL operations continue normally.
- **Datomic** - Required for core operations. The service will not start without a valid connection.

---

## Documentation

| Document | Description |
|----------|-------------|
| [ARCHITECTURE.md](./ARCHITECTURE.md) | Diplomat Architecture specification and layer access rules |
| [TESTING.md](./TESTING.md) | Testing guide, patterns and statistics (39 tests, 182 assertions) |
| [SETUP.md](./SETUP.md) | Prerequisites, getting started, API usage and configuration           |