# Clojure Url Shortener

![Paper URL Shortener](https://substackcdn.com/image/fetch/$s_!QCw7!,f_auto,q_auto:good,fl_progressive:steep/https%3A%2F%2Fsubstack-post-media.s3.amazonaws.com%2Fpublic%2Fimages%2F57794829-95e3-4e38-a615-34527d6cecdd_2374x1370.png)

![Clojure](https://img.shields.io/badge/Clojure-91DC47?style=for-the-badge&logo=clojure&logoColor=5881D8) ![Datomic](https://img.shields.io/badge/Datomic-2C2C2C?style=for-the-badge&logo=databricks&logoColor=FF3621) ![Apache Kafka](https://img.shields.io/badge/Apache_Kafka-231F20?style=for-the-badge&logo=apachekafka&logoColor=white) ![Pedestal](https://img.shields.io/badge/Pedestal-91DC47?style=for-the-badge&logo=clojure&logoColor=5881D8) ![Docker](https://img.shields.io/badge/Docker-2496ED?style=for-the-badge&logo=docker&logoColor=white)

![Status](https://img.shields.io/badge/Status-Active-success?style=flat-square) ![License](https://img.shields.io/badge/License-Academic-blue?style=flat-square)

A production-grade URL shortener written in Clojure, following the Diplomat Architecture pattern. Uses Datomic for immutable URL storage and time-travel analytics, and Apache Kafka for real-time click event streaming. Exposes a RESTful API via Pedestal with support for custom short codes.

---

## Stack

| Icon | Concern | Technology |
| --- | --- | --- |
| ![Clojure](https://img.shields.io/badge/-91DC47?style=flat-square&logo=clojure&logoColor=5881D8) | Language | Clojure |
| ![Pedestal](https://img.shields.io/badge/-1E1E1E?style=flat-square&logo=ring&logoColor=white) | HTTP server | Pedestal + Ring |
| ![Datomic](https://img.shields.io/badge/-1B1B1B?style=flat-square&logo=databricks&logoColor=FF3621) | Database | Datomic |
| ![Kafka](https://img.shields.io/badge/-231F20?style=flat-square&logo=apachekafka&logoColor=white) | Event streaming | Apache Kafka |
| ![Schema](https://img.shields.io/badge/-1B1B1B?style=flat-square&logo=clojure&logoColor=91DC47) | Schema validation | Plumatic Schema |
| ![deps](https://img.shields.io/badge/-1B1B1B?style=flat-square&logo=clojure&logoColor=5881D8) | Build tool | deps.edn (tools.deps) |
| ![Testing](https://img.shields.io/badge/-1B1B1B?style=flat-square&logo=testcafe&logoColor=white) | Testing | clojure.test + test.check |

---

## Architecture

Follows the **Diplomat Architecture** (Hexagonal Architecture variant), strictly separating domain logic from infrastructure. See [ARCHITECTURE.md](./ARCHITECTURE.md) for the full specification.

```text
External World
    |
diplomat/  (http-server, producer, consumer, datomic)
    |
wire/ <--> adapters/
    |
controllers/  ->  logic/  ->  models/
```

---

## API

| Method   | Endpoint                  | Description              |
|----------|---------------------------|--------------------------|
| `POST`   | `/api/urls`               | Shorten a URL            |
| `GET`    | `/:code`                  | Redirect to original URL |
| `GET`    | `/api/urls/:code/stats`   | Get click analytics      |
| `DELETE` | `/api/urls/:code`         | Deactivate a short URL   |
