# Clojure Url Shortener

![Paper URL Shortener](https://i.imgur.com/ifyjyTh.jpeg)

<div align="center">

![Clojure](https://img.shields.io/badge/Clojure-91DC47?style=for-the-badge&logo=clojure&logoColor=5881D8)
![Nubank](https://img.shields.io/badge/Nubank-820AD1?style=for-the-badge&logo=nubank&logoColor=white)
![Datomic](https://img.shields.io/badge/Datomic-FF3621?style=for-the-badge&logo=databricks&logoColor=white)
![Apache Kafka](https://img.shields.io/badge/Apache_Kafka-231F20?style=for-the-badge&logo=apachekafka&logoColor=white)
![Apache Spark](https://img.shields.io/badge/Apache_Spark-E25A1C?style=for-the-badge&logo=apachespark&logoColor=white)
![Pedestal](https://img.shields.io/badge/Pedestal-5881D8?style=for-the-badge&logo=clojure&logoColor=white)
![Redis](https://img.shields.io/badge/Redis-DC382D?style=for-the-badge&logo=redis&logoColor=white)
![Nginx](https://img.shields.io/badge/Nginx-009639?style=for-the-badge&logo=nginx&logoColor=white)
![Docker](https://img.shields.io/badge/Docker-2496ED?style=for-the-badge&logo=docker&logoColor=white)

![Status](https://img.shields.io/badge/Status-Active-91DC47?style=flat-square&logo=checkmarx&logoColor=white)
![License](https://img.shields.io/badge/License-Academic-820AD1?style=flat-square&logo=bookstack&logoColor=white)

</div>

A production-grade URL shortener written in Clojure, following the Diplomat Architecture pattern. Uses Datomic for immutable URL storage and time-travel analytics, and Apache Kafka for real-time click event streaming. Exposes a RESTful API via Pedestal with support for custom short codes.

---

## Stack

| Icon | Concern | Technology |
| --- | --- | --- |
| ![Clojure](https://img.shields.io/badge/-91DC47?style=flat-square&logo=clojure&logoColor=5881D8) | Language | Clojure |
| ![Pedestal](https://img.shields.io/badge/-5881D8?style=flat-square&logo=clojure&logoColor=white) | HTTP Server / API Gateway | Pedestal + Ring |
| ![Nginx](https://img.shields.io/badge/-009639?style=flat-square&logo=nginx&logoColor=white) | Load Balancer | Nginx |
| ![Docker](https://img.shields.io/badge/-2496ED?style=flat-square&logo=docker&logoColor=white) | Containerization | Docker |
| ![Datomic](https://img.shields.io/badge/-FF3621?style=flat-square&logo=databricks&logoColor=white) | Database / URL Storage | Datomic |
| ![Redis](https://img.shields.io/badge/-DC382D?style=flat-square&logo=redis&logoColor=white) | Caching | Redis |
| ![Kafka](https://img.shields.io/badge/-231F20?style=flat-square&logo=apachekafka&logoColor=white) | Event Streaming | Apache Kafka |
| ![Spark](https://img.shields.io/badge/-E25A1C?style=flat-square&logo=apachespark&logoColor=white) | Analytics Processing | Apache Spark |
| ![Schema](https://img.shields.io/badge/-820AD1?style=flat-square&logo=clojure&logoColor=white) | Schema Validation | Plumatic Schema |
| ![deps](https://img.shields.io/badge/-91DC47?style=flat-square&logo=clojure&logoColor=5881D8) | Build Tool | deps.edn (tools.deps) |
| ![Testing](https://img.shields.io/badge/-1B1B1B?style=flat-square&logo=testcafe&logoColor=white) | Testing | clojure.test + test.check |

---

## Architecture

Follows the **Diplomat Architecture** (Hexagonal Architecture variant), strictly separating domain logic from infrastructure. See [ARCHITECTURE.md](./ARCHITECTURE.md) for the full specification.

![Paper URL Shortener](https://i.imgur.com/8nf5PzT.jpeg)
---

## API

| Method   | Endpoint                  | Description              |
|----------|---------------------------|--------------------------|
| `POST`   | `/api/urls`               | Shorten a URL            |
| `GET`    | `/:code`                  | Redirect to original URL |
| `GET`    | `/api/urls/:code/stats`   | Get click analytics      |
| `DELETE` | `/api/urls/:code`         | Deactivate a short URL   |
