# Task Manager Microservices

[![CI](https://github.com/Toleflaco/task-manager-microservices/actions/workflows/ci.yml/badge.svg)](https://github.com/Toleflaco/task-manager-microservices/actions/workflows/ci.yml)

**Java 21 · Spring Boot 3.5 · Spring Cloud Gateway · PostgreSQL · MongoDB · JWT · Testcontainers · Docker Compose**

Decomposition of [`task-manager-api`](https://github.com/Toleflaco/task-manager-api)
into a microservices architecture with an API Gateway and two backing
services. It is the continuation of my self-taught Java backend learning
roadmap, focused on distributed system patterns: bounded-context
decomposition, border authentication with JWT validation at the edge,
and header-propagated identity to downstream services that no longer
validate tokens themselves.

The system is composed of three independently deployable Spring Boot
services communicating over HTTP. The **API Gateway** (Spring Cloud
Gateway on Netty) is the only entry point exposed to clients: it
validates JWTs signed by the auth service, extracts the user id from
the `sub` claim, and propagates it as an `X-User-Id` header to
downstream services. The **auth service** owns user registration,
login, refresh-token rotation, and JWT issuance. The **task service**
owns tasks, categories, and the activity audit log — it trusts the
`X-User-Id` header injected by the gateway and never validates JWTs
itself. This is the *border authentication* pattern: authentication
happens once, at the edge, and internal services confine themselves to
their business responsibility.

Each service is built as a Maven module under a single reactor parent,
with its own database schema, its own Flyway migrations, and its own
Testcontainers setup for local development. The system runs end-to-end
via Docker Compose (see [Getting started](#getting-started)) and is
the operational core of Phase 10 of the roadmap.

## Tech stack

- **Java 21** — modern language features (records, pattern matching, virtual threads support).
- **Spring Boot 3.5.4** — application framework across the three services (downgraded from monolith's Spring Boot 4.x for Spring Cloud 2025.0.0 compatibility).
- **Spring Cloud Gateway 2025.0.0 (Northfields)** — reactive gateway on Netty, declarative routing, `GlobalFilter` for JWT validation.
- **Spring Security 6** — servlet-stack security in the task service, custom `OncePerRequestFilter` for header-based authentication.
- **Spring Data JPA + Hibernate 6** — ORM in both services, JPA Specifications, entity graphs, optimistic locking with `@Version`.
- **Spring Data MongoDB** — activity audit log in the task service.
- **PostgreSQL 14** — one database instance with schema-per-service (`auth_schema`, `task_schema`).
- **Flyway** — versioned schema migrations per service, `ddl-auto: validate` in every environment.
- **jjwt 0.12.6** — JWT signing (HMAC-SHA256) in the auth service and verification in the gateway.
- **BCrypt** — password hashing.
- **Bucket4j 8.10.1** — token-bucket rate limiting on the auth service's `/auth/login`.
- **Testcontainers 2.0.5** — PostgreSQL 14 and MongoDB 6.0 containers with `@ServiceConnection` for local development and integration testing. Container reuse enabled to keep dev iterations fast.
- **JUnit 5 + Mockito + AssertJ** — unit tests carried over from the monolith with BDDMockito style.
- **Maven multi-module** — single reactor `pom.xml` with three sub-modules (`api-gateway`, `auth-service`, `task-service`), shared `<dependencyManagement>` for Spring Cloud BOM, Testcontainers BOM, jjwt, and MapStruct.
- **MapStruct 1.6.3** — compile-time DTO ↔ entity mapping with `ReportingPolicy.ERROR` for fail-fast on field changes.
- **SLF4J + Logback** — structured logging.
- **Spring Boot Actuator** — health endpoints in all three services (`/actuator/health`), plus `/actuator/gateway/routes` on the gateway for route introspection.
- **Jakarta Validation** — request body validation via annotations.
- **Docker Compose** — five-container orchestration (PostgreSQL, MongoDB, and the three Spring services) on an internal bridge network with cascading health checks. Only the gateway publishes a port to the host, enforcing the border-authentication trust boundary at the network level.

## Architecture

Three services, one entry point, border authentication:

```mermaid
flowchart LR
    Client([Client])
    subgraph Gateway["API Gateway :8080"]
        JwtFilter[JwtValidationFilter<br/>validates signature<br/>extracts sub → X-User-Id]
    end
    subgraph Auth["Auth Service :8081"]
        AuthCtrl[AuthController<br/>UserController]
        JwtSvc[JwtService<br/>signs tokens]
    end
    subgraph Task["Task Service :8082"]
        HeaderFilter[HeaderAuthenticationFilter<br/>reads X-User-Id<br/>trusts it]
        Controllers[Task / Category /<br/>Activity Controllers]
    end
    PG_Auth[(PostgreSQL<br/>auth_schema)]
    PG_Task[(PostgreSQL<br/>task_schema)]
    Mongo[(MongoDB<br/>activity_events)]

    Client -->|"HTTP + Bearer JWT"| Gateway
    Gateway -->|"/auth/**, /users/**"| Auth
    Gateway -->|"/tasks/**, /categories/**,<br/>/me/activity/** + X-User-Id"| Task
    Auth --> PG_Auth
    Task --> PG_Task
    Task --> Mongo
```

The full rationale for placing JWT validation at the gateway (and not
at each service), the trust boundary implied by header propagation,
and the alternatives considered are documented in
[`docs/adr-004-border-authentication.md`](docs/adr-004-border-authentication.md).

**Trust boundary.** The gateway is the only service exposed to clients.
In production, the auth service and task service live on an internal
network (Docker Compose network today, VPC subnets in a cloud
deployment tomorrow) and are not directly reachable. This makes the
downstream trust in `X-User-Id` a valid architectural choice, not a
security gap: any request that reaches the task service has, by
construction, already been authenticated at the border.

**Bounded contexts.** The service split follows the domain boundary
made explicit during the monolith work: identity concerns
(registration, credentials, tokens) live in the auth service; task
management concerns (tasks, categories, activity log) live in the task
service. Cross-service foreign keys do not exist at the database
level — each service stores foreign identifiers as regular `BIGINT`
columns, with referential integrity enforced at the application layer
through the propagated user id.

**Polyglot persistence in the task service.** PostgreSQL for
transactional data (tasks, categories) and MongoDB for the append-only
activity audit log — the same rationale documented in the monolith's
[ADR-001](https://github.com/Toleflaco/task-manager-api/blob/main/docs/adr-001-polyglot-persistence.md).

## Getting started

Two ways to run the system: **Docker Compose** for a full end-to-end
stack in one command, or **Spring Boot `test-run`** for iterating on
one service at a time against Testcontainers-managed infrastructure.

### Requirements

- Docker Desktop (or Docker Engine on Linux).
- Java 21 (Temurin recommended) — only for the `test-run` path.
  The Compose path does not require a JDK on the host: the Maven
  build runs inside the builder stage of each Dockerfile.

### Option 1 — Docker Compose (recommended for first look)

The compose stack wires five containers into a single internal bridge
network: PostgreSQL 14, MongoDB 6.0, the three Spring services, and
health-check-driven start ordering. Only the gateway publishes a port
to the host (8080). The auth and task services live on the internal
network and are not reachable from outside — this is the trust
boundary of the border-authentication pattern made operational.

First-time setup — create a local `.env` from the template and fill
in secrets:

```bash
cp .env.example .env
# then edit .env with real values (see .env.example for guidance)
```

Then bring the stack up:

```bash
docker compose up --build -d
```

Watch the health cascade until every container reports `healthy`:

```bash
docker compose ps
```

Expected once ready:

```
NAME               STATUS                    PORTS
tmm-api-gateway    Up (healthy)              0.0.0.0:8080->8080/tcp
tmm-auth-service   Up (healthy)              8081/tcp
tmm-task-service   Up (healthy)              8082/tcp
tmm-postgres       Up (healthy)              5432/tcp
tmm-mongo          Up (healthy)              27017/tcp
```

Note that only `tmm-api-gateway` publishes to the host. The other
services expose ports only within the compose network.

Tear down (keeping data volumes):

```bash
docker compose down
```

Full reset (removes Postgres and Mongo data volumes — needed if you
change the Postgres init script):

```bash
docker compose down -v
```

### Option 2 — Spring Boot `test-run` (recommended for daily development)

Each service can be run independently against Testcontainers-managed
infrastructure using the Spring Boot `test-run` goal. This starts the
service against a fresh PostgreSQL (and MongoDB for the task service)
container, applying Flyway migrations at startup, without requiring
docker-compose or `.env`.

Three terminals, one per service, from the repository root:

```bash
# Terminal 1 — auth-service on port 8081
./mvnw -pl auth-service spring-boot:test-run

# Terminal 2 — task-service on port 8082
./mvnw -pl task-service spring-boot:test-run

# Terminal 3 — api-gateway on port 8080
./mvnw -pl api-gateway spring-boot:run
```

The gateway does not require Testcontainers (it has no database of
its own), so it uses the plain `spring-boot:run` goal. The auth and
task services use `spring-boot:test-run`, which activates the
`TestcontainersConfig` under `src/test/java` and wires the datasource
via `@ServiceConnection`. Container reuse is enabled, so subsequent
restarts pick up the already-running containers and start in seconds.

### End-to-end flow

Once the stack is up (either option), a full flow through the gateway
looks like this:

```bash
# 1. Register a user (public, no JWT required)
curl -X POST http://localhost:8080/users \
  -H "Content-Type: application/json" \
  -d '{"name":"Alice","email":"alice@example.com","password":"Password123!"}'

# 2. Log in and receive tokens (public)
curl -X POST http://localhost:8080/auth/login \
  -H "Content-Type: application/json" \
  -d '{"email":"alice@example.com","password":"Password123!"}'
# → { "accessToken": "eyJhbGciOi...", "refreshToken": "...", "expiresIn": 900 }

# 3. Create a category with the access token
curl -X POST http://localhost:8080/categories \
  -H "Authorization: Bearer <accessToken>" \
  -H "Content-Type: application/json" \
  -d '{"name":"Work","description":"Work-related tasks"}'
```

The gateway validates the JWT, extracts the subject as user id, and
propagates it as `X-User-Id` to the task service, which uses it for
authorization and to populate `createdBy` on the entity through JPA
auditing. In parallel, the task service publishes an
`ApplicationEvent` that persists an `ActivityEvent` document in
MongoDB — polyglot persistence coordinated in-process without
distributed transactions.

### Health endpoints

Only the gateway is reachable from the host:

```bash
curl http://localhost:8080/actuator/health   # gateway (public)
```

The gateway additionally exposes `/actuator/gateway/routes` in
read-only mode for route introspection. The auth and task services'
health endpoints are only reachable from within the compose network:

```bash
docker compose exec api-gateway wget -qO- http://auth-service:8081/actuator/health
docker compose exec api-gateway wget -qO- http://task-service:8082/actuator/health
```

## Repository layout

```
task-manager-microservices/
├── api-gateway/              # Spring Cloud Gateway, JWT validation, routing
├── auth-service/             # Users, credentials, tokens
├── task-service/             # Tasks, categories, activity log
├── docker/postgres/init/     # Postgres schema-per-service init script
├── docs/                     # Architecture Decision Records (ADRs)
├── Dockerfile.auth           # Multi-stage build for auth-service
├── Dockerfile.gateway        # Multi-stage build for api-gateway
├── Dockerfile.task           # Multi-stage build for task-service
├── docker-compose.yml        # 5-container orchestration with health cascade
├── .env.example              # Template for local secrets (copy to .env)
└── pom.xml                   # Parent reactor with shared dependency management
```

## Related work

- [`task-manager-api`](https://github.com/Toleflaco/task-manager-api) —
  the monolith this project derives from. All decisions on domain
  modelling, persistence patterns, security implementation and testing
  strategy were made and documented there first. This repository
  applies those decisions to a distributed context and adds the ones
  specific to it.


## Future work

The current codebase covers the operational core of a distributed
system: bounded-context decomposition, border authentication with
JWT validation at the edge and `X-User-Id` propagation to downstream
services, schema-per-service persistence with Flyway, per-service
Testcontainers for isolated development, GitHub Actions CI, and
end-to-end orchestration via Docker Compose with a health-check
cascade that makes container start ordering deterministic.

The following distributed-system patterns are intentionally deferred
and will be layered on top of this foundation as separate iterations:

- **Resilience.** Circuit Breaker with Resilience4j on gateway →
  downstream calls, retries with exponential back-off, bulkheads.
- **Distributed tracing.** Micrometer Tracing with a Zipkin or Tempo
  backend to follow a request across the three services.
- **Async messaging.** RabbitMQ for events that don't need synchronous
  responses (activity log fan-out, notifications).
- **Saga / outbox.** Transactional outbox for reliable event
  publication tied to Postgres commits.
- **Contract testing.** Consumer-driven contracts between the gateway
  and downstream services (Spring Cloud Contract or Pact).
- **Distributed rate limiting and caching.** Redis-backed Bucket4j
  and second-level cache.

Each of these is worth its own ADR and dedicated iteration rather
than a rushed inclusion.
