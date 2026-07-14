# ADR-004: Border Authentication with JWT at the Gateway

## Status

Accepted — 2026-07-14.

## Context

The system splits a previously monolithic API into three independently
deployable Spring Boot services: an **API Gateway** (Spring Cloud
Gateway on Netty), an **auth service** (registration, credentials,
token issuance), and a **task service** (tasks, categories, activity
log). Clients reach the system exclusively through the gateway. Auth
and task services live on an internal network — a Docker Compose
bridge network today, VPC private subnets in a future cloud deployment.

The monolith used JWT bearer authentication with a filter that
validated the token on every request. Splitting the monolith raises
a question that did not exist before: **where does JWT validation
live in a distributed system?** Each downstream service could
continue to validate the token itself (a symmetric transplant of the
monolith's pattern), or the validation could happen once at the
edge and be trusted afterwards, or the boundary could be pushed all
the way down using service-to-service certificates.

The choice affects security posture, coupling, operational cost,
and how much cryptographic work is repeated per request. It also
affects the shape of every downstream service: whether they carry a
JWT dependency at all, whether they need the signing key, how they
identify the caller, and what failure modes they must handle.

Constraints acknowledged for this decision:

- The system runs on a private internal network. The gateway is the
  only publicly reachable component.
- All services are owned by the same team and evolve together. No
  third-party clients call the internal services directly.
- Load and threat model are those of a portfolio project moving
  toward small-to-medium production use — not a zero-trust
  multi-tenant environment.

## Decision

Adopt **border authentication**: JWT validation happens once, at the
API Gateway. Downstream services do not validate JWTs; they receive
an authenticated user identity as a propagated HTTP header
(`X-User-Id`) and trust it.

Concretely:

- The gateway exposes a `GlobalFilter` (`JwtValidationFilter`) that
  intercepts every non-public route, verifies the JWT signature with
  the shared HS256 key, checks expiration, extracts the `sub` claim,
  and injects it as an `X-User-Id` header on the upstream request.
- Public routes (`POST /users`, `POST /auth/login`,
  `POST /auth/refresh`) are declared explicitly and bypass the
  filter.
- The auth service signs tokens with the same key. It is the only
  other service that holds the signing secret.
- The task service does not depend on jjwt at all. It carries a
  Spring Security `OncePerRequestFilter`
  (`HeaderAuthenticationFilter`) that reads `X-User-Id` from the
  request, builds an `Authentication` populated with that id, and
  places it in the `SecurityContext`. If the header is missing, the
  request is rejected with `401`.
- The JWT signing key is externalised as an environment variable
  (`JWT_SECRET`) in both the gateway and the auth service. In local
  development it comes from a git-ignored `.env`; in a real
  deployment it would come from a secret manager. The task service
  does not receive this variable.
- The trust boundary is enforced at the network layer, not only at
  the application layer: in `docker-compose.yml`, only the gateway
  publishes a port to the host. Auth and task services expose their
  ports only inside the compose bridge network.

### Alternatives considered

**Alternative A — JWT-per-service (symmetric validation).**
Each downstream service revalidates the token on every request,
mirroring the monolith's original approach. Reasoned rejection:
every service would need the jjwt dependency, the shared signing
key, and the same expiration/blacklist logic. Every request would
pay the cryptographic cost of validation twice or three times. Key
rotation would need to be coordinated across services. In exchange
for this cost, the marginal security gain over border authentication
on a private network is small: a compromised internal service
already has database access; verifying a JWT it received from the
gateway does not change that threat model. This alternative fits
zero-trust deployments with untrusted internal networks, which is
explicitly not this system's context.

**Alternative B — mTLS between services.**
Push the boundary further down with mutual TLS certificates: the
gateway presents a certificate to the auth or task service, and
each downstream service verifies it. Reasoned rejection: heavy
operational cost (certificate lifecycle, rotation, revocation,
per-service PKI configuration) for a system where the gateway is
already the single ingress point and internal services are
network-isolated. mTLS is the right pattern for service meshes
(Istio, Linkerd) or truly zero-trust deployments; it is
overengineering for this scope. It remains available as a future
tightening if the deployment model changes.

**Alternative C — Opaque tokens with a central introspection endpoint.**
Downstream services send the token to a `/introspect` endpoint on
the auth service and receive an authoritative response. Reasoned
rejection: adds a network hop and a hard runtime dependency on the
auth service to every downstream request. Loses the stateless
advantage that made JWT attractive in the first place. Introspection
is the right choice when tokens must be revocable in near real time,
which is not a requirement here — access tokens are short-lived
(15 minutes) and refresh tokens are already single-use and rotated.

## Consequences

### Positive

- **Single point of validation.** The signing key lives in exactly
  two places (gateway and auth service). Key rotation is a two-service
  change, not a three-service change. Downstream services do not care
  what token format the edge accepts — the gateway can be switched
  from JWT to opaque tokens later without touching task-service code.
- **Downstream services stay focused on their domain.** Task service
  has no jjwt dependency, no `application.yml` entry for JWT, no
  security-configuration coupling to how tokens are issued. Its
  security filter is a plain `OncePerRequestFilter` that reads one
  header.
- **Cryptographic cost paid once per request.** Signature verification
  runs at the gateway only.
- **Trust boundary is visible and enforceable at the network layer.**
  `docker compose ps` shows only the gateway publishing a port; a
  `curl localhost:8081` from the host receives connection-refused.
  The boundary is not a comment in a config file, it is the operational
  reality of the deployment.
- **Consistent with the intended production topology.** In a cloud
  deployment (ECS, Kubernetes), the same shape maps to a public
  load balancer in front of the gateway with the auth and task
  services in a private subnet or ClusterIP-only service.

### Negative

- **Downstream services trust the gateway completely.** A compromised
  or misconfigured gateway can impersonate any user by forging the
  `X-User-Id` header. Mitigations: the gateway is the smallest and
  most audited service; it has no database of its own and thus a
  smaller attack surface; and the trust boundary is reinforced at
  the network layer (services are unreachable from outside).
- **A request that bypasses the gateway would bypass authentication.**
  This is why network-level isolation is not optional — it is a
  co-requisite of the decision, not a nice-to-have.
- **Header spoofing is trivial if the network boundary breaks.** A
  test that forgets to route through the gateway and calls
  `task-service` directly with `X-User-Id: 1` would succeed. This is
  a real risk in local development and must be handled by convention:
  integration tests target the gateway's port, never the internal
  service ports.
- **The gateway becomes a scaling bottleneck for cryptographic work.**
  For the current load profile this is a non-issue; at higher
  throughput, horizontal scaling of the gateway (which is
  reactive/Netty) is straightforward.

### Neutral

- The auth service and gateway must agree on the signing algorithm
  (HS256), the key, and the expiration policy. This coupling is
  intentional and is the only cross-service coupling introduced by
  this decision.

## Verification

The decision is verifiable operationally with the running compose
stack. From the repository root, after `docker compose up --build -d`:

**Trust boundary at the network layer:**

```bash
docker compose ps
# Only tmm-api-gateway lists a host port (0.0.0.0:8080->8080/tcp).
# tmm-auth-service and tmm-task-service list only internal ports.

curl -s -o /dev/null -w "%{http_code}\n" http://localhost:8080/actuator/health
# 200 — gateway reachable from host.

curl -s -o /dev/null -w "%{http_code}\n" http://localhost:8081/actuator/health
# 000 — connection refused, auth-service unreachable from host.

curl -s -o /dev/null -w "%{http_code}\n" http://localhost:8082/actuator/health
# 000 — connection refused, task-service unreachable from host.
```

**Internal reachability preserved:**

```bash
docker compose exec api-gateway wget -qO- http://auth-service:8081/actuator/health
# {"status":"UP"}
docker compose exec api-gateway wget -qO- http://task-service:8082/actuator/health
# {"status":"UP"}
```

**End-to-end propagation of identity:**

```bash
# Register, log in, capture the access token.
curl -s -X POST http://localhost:8080/users \
  -H "Content-Type: application/json" \
  -d '{"name":"Alice","email":"alice@example.com","password":"Password123!"}'

TOKEN=$(curl -s -X POST http://localhost:8080/auth/login \
  -H "Content-Type: application/json" \
  -d '{"email":"alice@example.com","password":"Password123!"}' \
  | jq -r '.accessToken')

# Create a category through the gateway.
curl -s -X POST http://localhost:8080/categories \
  -H "Authorization: Bearer $TOKEN" \
  -H "Content-Type: application/json" \
  -d '{"name":"Work","description":"Work-related tasks"}'
```

The category is persisted in Postgres with `created_by = 1`, matching
the `sub` claim of the JWT. In parallel, an `ActivityEvent` document
is written to MongoDB with the same `userId: 1`. The identity flows
end-to-end without the task service ever inspecting a token.

## Related decisions

- Polyglot persistence in the task service:
  [`task-manager-api` ADR-001](https://github.com/Toleflaco/task-manager-api/blob/main/docs/adr-001-polyglot-persistence.md).
- Externalisation of the JWT signing key as an environment variable,
  with a base64-encoded developer-only default in `application.yml`
  and the real value provided via a git-ignored `.env` file
  consumed by Docker Compose.
