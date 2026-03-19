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
docker-compose up -d redis kafka
```

This starts Redis (port 6379) and Kafka in KRaft mode (port 9092).

### 4. Run the application

```bash
lein run
```

The API starts on `http://localhost:8080`.

### 5. Run the full stack with Docker

```bash
docker-compose up -d
```

This builds and starts the app along with Redis and Kafka.

## API Usage

### Register a user

```bash
curl -X POST http://localhost:8080/api/auth/register \
  -H "Content-Type: application/json" \
  -d '{"username": "myuser", "password": "mypassword123"}'
```

**Response** (201):
```json
{
  "id": "550e8400-e29b-41d4-a716-446655440000",
  "username": "myuser"
}
```

### Login

```bash
curl -X POST http://localhost:8080/api/auth/login \
  -H "Content-Type: application/json" \
  -d '{"username": "myuser", "password": "mypassword123"}'
```

**Response** (200):
```json
{
  "token": "eyJhbGciOiJIUzI1NiJ9...",
  "expires-in": 3600
}
```

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
  "created-at": "2026-03-17T15:30:00.000Z",
  "last-accessed": "2026-03-17T16:00:00.000Z",
  "unique-visitors": 15
}
```

### Get daily analytics (requires auth)

```bash
curl http://localhost:8080/api/urls/A1b2C3d4/analytics \
  -H "Authorization: Bearer eyJhbGciOiJIUzI1NiJ9..."
```

**Response** (200):
```json
{
  "short-code": "A1b2C3d4",
  "daily-analytics": [
    {"date": "2026-03-17T00:00:00Z", "clicks": 25, "unique-visitors": 10},
    {"date": "2026-03-18T00:00:00Z", "clicks": 17, "unique-visitors": 5}
  ]
}
```

### Deactivate a URL (requires auth)

```bash
curl -X DELETE http://localhost:8080/api/urls/A1b2C3d4 \
  -H "Authorization: Bearer eyJhbGciOiJIUzI1NiJ9..."
```

### Prometheus metrics

```bash
curl http://localhost:8080/metrics
```

## Configuration

Application configuration lives in `resources/config.edn` and supports environment-specific profiles:

| Key | Description | Default |
|-----|-------------|---------|
| `:datomic-uri` | Datomic connection URI | `datomic:mem://url-shortener` |
| `:redis-host` | Redis host | `localhost` |
| `:redis-port` | Redis port | `6379` |
| `:kafka-bootstrap-servers` | Kafka broker address | `localhost:9092` |
| `:port` | HTTP server port | `8080` |
| `:base-url` | Base URL for generated short links | `http://localhost:8080/r` |
| `:jwt-secret` | Secret key for JWT signing | `dev-secret-change-in-production` |
| `:jwt-ttl-minutes` | JWT token time-to-live in minutes | `60` |
| `:rate-limiter` | Rate limit config per route group | See `config.edn` |

## Running Tests

All tests run self-contained with in-memory Datomic and NoOp mocks for Redis and Kafka.

```bash
lein test            # All tests
lein test-unit       # Unit tests only (fast)
lein test-integration # Integration tests only
lein coverage        # Coverage report
```

See [TESTING.md](./TESTING.md) for the full testing guide.
