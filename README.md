# Task Manager Microservices

[![CI](https://github.com/Toleflaco/task-manager-microservices/actions/workflows/ci.yml/badge.svg?branch=main)](https://github.com/Toleflaco/task-manager-microservices/actions/workflows/ci.yml)
[![License: MIT](https://img.shields.io/badge/License-MIT-yellow.svg)](https://opensource.org/licenses/MIT)
[![Java 21](https://img.shields.io/badge/Java-21-orange.svg)](https://openjdk.org/projects/jdk/21/)
[![Spring Boot 4.0](https://img.shields.io/badge/Spring%20Boot-4.0-brightgreen.svg)](https://spring.io/projects/spring-boot)
[![Kafka](https://img.shields.io/badge/Kafka-3.7-black.svg)](https://kafka.apache.org/)

Monolith decomposition into event-driven microservices architecture: Auth Service, Task Service, API Gateway. Kafka as event bus, Resilience4j for distributed resilience, Docker Compose for local orchestration.

## Tech Stack

- **Java 21**
- **Spring Boot 4.0** — Spring Cloud Gateway, Spring Data JPA
- **Spring Cloud Gateway** — API Gateway with border-authentication JWT
- **Resilience4j** — Circuit Breaker, Retry, TimeLimiter (matryoshka composition)
- **Apache Kafka** — event bus with @TransactionalEventListener(AFTER_COMMIT)
- **PostgreSQL** — schema-per-service (isolation)
- **Redis** — distributed cache and sessions
- **Docker Compose** — cascading healthchecks
- **GitHub Actions** — multi-stage CI/CD
- **ADRs (Nygard format)** — documented architectural decisions

## Architecture

```
┌─────────────────────────────────────────────┐
│         Client / Frontend / MCP             │
└──────────────────┬──────────────────────────┘
                   │ HTTP + JWT
                   v
        ┌──────────────────────┐
        │   API Gateway        │ (Spring Cloud Gateway)
        │   Port 8080          │ Border-authentication
        │                      │ (validates JWT, adds X-User-Id)
        └──────────┬───────────┘
                   │
      ┌────────────┴────────────┐
      │                         │
      v                         v
 ┌──────────────┐        ┌──────────────┐
 │Auth Service  │        │ Task Service │
 │Port 8081     │        │ Port 8082    │
 │              │        │              │
 │• register    │        │• create task │
 │• login       │        │• update task │
 │• verify JWT  │        │• delete task │
 └──────┬───────┘        └──────┬───────┘
        │                       │
        └───────────┬───────────┘
                    │
        ┌───────────v──────────────┐
        │   Kafka Event Bus        │
        │ (task.created,           │
        │  task.updated,           │
        │  user.registered, ...)   │
        └───────────┬──────────────┘
                    │
      ┌─────────────┴─────────────┐
      │                           │
      v                           v
┌──────────────────┐     ┌──────────────────┐
│  PostgreSQL      │     │     Redis        │
│ (auth schema)    │     │  (cache)         │
│                  │     │  (sessions)      │
│• users           │     │  (distributed    │
│• refresh_tokens  │     │   locks)         │
└──────────────────┘     └──────────────────┘

┌──────────────────┐
│  PostgreSQL      │
│ (task schema)    │
│                  │
│• tasks           │
│• task_events     │
└──────────────────┘
```

## Quick Start

### Requirements

- Java 21+
- Maven 3.9+
- Docker and Docker Compose
- Git

### Installation (with Docker Compose)

1. Clone the repository:
   ```bash
   git clone https://github.com/Toleflaco/task-manager-microservices.git
   cd task-manager-microservices
   ```

2. Start the entire stack (services + databases + Kafka + Redis):
   ```bash
   docker compose up --build
   ```

   This starts:
  - **API Gateway** on `localhost:8080` (entry point)
  - **Auth Service** on `localhost:8081`
  - **Task Service** on `localhost:8082`
  - **PostgreSQL** (2 instances, one per service)
  - **Kafka + Zookeeper** (event bus)
  - **Redis** (cache and sessions)

3. Wait for healthchecks to pass (30–60 seconds):
   ```bash
   docker compose logs -f api-gateway
   ```

   When you see something like `Netty started on port 8080`, it's ready.

4. Verify services are responding:
   ```bash
   # API Gateway
   curl http://localhost:8080/actuator/health
   
   # Auth Service
   curl http://localhost:8081/actuator/health
   
   # Task Service
   curl http://localhost:8082/actuator/health
   ```

### Architecture Flow

```
request: POST /api/tasks (client)
         ↓
    API Gateway (8080)
    - Validates JWT
    - Extracts userId → X-User-Id header
    - Routes to Task Service
         ↓
    Task Service (8082)
    - Authenticates with X-User-Id
    - Creates Task in PostgreSQL
    - Publishes "task.created" event to Kafka
         ↓
    Auth Service listens for event on Kafka
    - Increments task counter in Redis
         ↓
    response: 201 Created + Location
```

### Complete Request Flow (step by step)

```
1. POST http://localhost:8080/api/tasks
   Authorization: Bearer eyJhbGc...
   Content-Type: application/json
   {"title": "Implement WebFlux"}

2. API Gateway (/mcp)
   - Validates JWT (secret in AuthService)
   - Extracts claims: userId=123
   - Adds header X-User-Id: 123
   - Routes to http://task-service:8082/api/tasks

3. Task Service
   - Reads header X-User-Id
   - Creates Task entity(userId=123, title=...)
   - Persists to PostgreSQL (task schema)
   - Publishes TaskCreated event to Kafka:
     {
       "eventType": "TaskCreated",
       "aggregateId": "task-456",
       "userId": 123,
       "timestamp": "2026-09-20T..."
     }
   - Responds 201 + Location: /api/tasks/456

4. Auth Service (Kafka subscriber)
   - Receives TaskCreated event
   - Looks up user 123 in Redis/PostgreSQL
   - Increments counter taskCount: 2 → 3
   - Publishes stats to Redis

5. Client receives 201 ✓
```

### Testing

All endpoints require JWT. First, register through the API Gateway:

```bash
# Registration (→ Auth Service via Gateway)
curl -X POST http://localhost:8080/api/auth/register \
  -H "Content-Type: application/json" \
  -d '{
    "email": "user@example.com",
    "password": "SecurePassword123!"
  }'

# Login (→ Auth Service via Gateway)
curl -X POST http://localhost:8080/api/auth/login \
  -H "Content-Type: application/json" \
  -d '{
    "email": "user@example.com",
    "password": "SecurePassword123!"
  }'

# Response includes:
# {
#   "accessToken": "eyJhbGc...",
#   "refreshToken": "eyJhbGc...",
#   "expiresIn": 3600
# }
```

Save the token and use it in requests:

```bash
TOKEN="eyJhbGc..."

# Create task (→ Task Service via Gateway)
curl -X POST http://localhost:8080/api/tasks \
  -H "Authorization: Bearer $TOKEN" \
  -H "Content-Type: application/json" \
  -d '{
    "title": "Implement Kafka",
    "description": "Event-driven integration"
  }'

# List tasks
curl "http://localhost:8080/api/tasks?page=0&size=10" \
  -H "Authorization: Bearer $TOKEN"

# Get task
curl http://localhost:8080/api/tasks/{taskId} \
  -H "Authorization: Bearer $TOKEN"

# Update task
curl -X PUT http://localhost:8080/api/tasks/{taskId} \
  -H "Authorization: Bearer $TOKEN" \
  -H "Content-Type: application/json" \
  -d '{"title": "Advanced Kafka"}'

# Delete task
curl -X DELETE http://localhost:8080/api/tasks/{taskId} \
  -H "Authorization: Bearer $TOKEN"
```

### Observing Kafka Events

Enter the Kafka container and consume events:

```bash
docker exec -it task-manager-microservices-kafka-1 \
  kafka-console-consumer.sh \
  --bootstrap-server localhost:9092 \
  --topic task-events \
  --from-beginning
```

You'll see something like:

```json
{"eventType": "TaskCreated", "aggregateId": "task-456", "userId": 123}
{"eventType": "TaskUpdated", "aggregateId": "task-456", "userId": 123}
```

### Logs

To view logs from a specific service:

```bash
# Auth Service
docker compose logs -f auth-service

# Task Service
docker compose logs -f task-service

# API Gateway
docker compose logs -f api-gateway

# Kafka
docker compose logs -f kafka
```

### Local Testing (without Docker)

If you want to develop locally against Docker services:

1. Start only databases:
   ```bash
   docker compose up postgres-auth postgres-task kafka redis -d
   ```

2. Start Auth Service from your IDE:
   ```bash
   cd auth-service
   ./mvnw spring-boot:run
   ```

3. In another terminal, start Task Service:
   ```bash
   cd task-service
   ./mvnw spring-boot:run
   ```

4. And API Gateway:
   ```bash
   cd api-gateway
   ./mvnw spring-boot:run
   ```

### Run Test Suite

```bash
# Tests for all services
./mvnw verify

# Tests for a specific service
cd auth-service
./mvnw verify
```

### Stop Everything

```bash
docker compose down

# Or to destroy volumes (clean DB on next run):
docker compose down -v
```

## Key Features

### Border Authentication

The API Gateway validates JWT and adds an `X-User-Id` header to all requests. Each service trusts this header (without re-validating JWT).

```java
// In Gateway
.addRequestHeader("X-User-Id", principalAsId)

// In Task Service
String userId = request.getHeader("X-User-Id");
```

### Composable Resilience (Resilience4j)

Each cross-service request traverses Circuit Breaker → Retry → TimeLimiter, in that order:

```
request
  ↓
[Circuit Breaker]  (opens if fail rate > 50%)
  ↓
[Retry]  (exponential backoff, max 3 attempts)
  ↓
[TimeLimiter]  (5s timeout by default)
  ↓
remote service
```

Details in `GatewayResilience.java`.

### Event-Driven with Kafka

- **@TransactionalEventListener(AFTER_COMMIT)** — event is published only if the DB transaction commits
- **No distributed transactions** — each service owns its schema
- **Eventually consistent** — Auth Service sees Task Service data with Kafka latency (~ms)

### Schema-per-Service

```
postgres (auth):
├── users
├── refresh_tokens
└── role_assignments

postgres (task):
├── tasks
├── task_events
└── task_attachments
```

Each service owns its schema. No foreign keys between schemas (loose coupling).

## Architectural Decisions (ADRs)

Documented in `decisions/`:

- **ADR-001-Border-Authentication** — Why validate JWT at Gateway, not in each service
- **ADR-002-Resilience-Composition** — Why Circuit Breaker → Retry → TimeLimiter in that order
- **ADR-003-Kafka-Transactional-Events** — Why @TransactionalEventListener(AFTER_COMMIT) prevents lost events

## Related Roadmaps

- **[Cloud Roadmap](https://github.com/Toleflaco/cloud-roadmap)** — ECS/EKS deployment, observability with Prometheus
- **[AI Engineer Roadmap](https://github.com/Toleflaco/ai-engineer-roadmap-java)** — Spring AI, MCP servers

---

*Last updated: 2026-09-20*
