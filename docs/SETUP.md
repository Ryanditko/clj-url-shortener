# Setup Guide

## Prerequisites

- **Java** 11+
- **Leiningen** 2.9+
- **Docker & Docker Compose** (for Redis and Kafka in local development)

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

This starts Redis (port 6379) and Kafka in KRaft mode (port 9092).

### 4. Run database migrations

```bash
lein migrate
```

### 5. Run the application

```bash
lein run
```
The API starts on `http://localhost:8080`.

## API Usage

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

### Deactivate a URL

```bash
curl -X DELETE http://localhost:8080/api/urls/A1b2C3d4
```

## Configuration

Application configuration lives in `resources/config.edn` and supports environment-specific profiles:

| Key | Description | Default |
|-----|-------------|---------|
| `:datomic-uri` | Datomic connection URI | `datomic:mem://url-shortener` |
| `:redis-host` | Redis host | `localhost` |
| `:redis-port` | Redis port | `6379` |
| `:kafka-bootstrap-servers` | Kafka broker address | `localhost:9092` |
| `:http-port` | HTTP server port | `8080` |

## Running Tests

All tests run self-contained with in-memory Datomic and NoOp mocks for Redis and Kafka.

```bash
lein test            # All tests
lein test-unit       # Unit tests only (fast)
lein test-integration # Integration tests only
lein coverage        # Coverage report
```

See [TESTING.md](./TESTING.md) for the full testing guide.
