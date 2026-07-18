# ADR-005: Externalising Downstream Hostnames via Per-Profile Placeholders

## Status

Accepted — 2026-07-15.

## Context

The `api-gateway` module supports two execution scenarios that differ
only in how it reaches the downstream services:

- **Local development** (`spring-boot:run` and `spring-boot:test-run`):
  each service runs on the developer's host, so route URIs must
  point at `localhost:808X`.
- **Docker Compose** (`docker compose up`): services live on an
  internal bridge network, so route URIs must point at internal DNS
  names (`auth-service:8081`, `task-service:8082`).

The original configuration — inherited from the initial gateway
scaffolding during Fase 10 operational — encoded this difference by
duplicating the entire `spring.cloud.gateway.server.webflux.routes`
list between two files: `application.yml` (base) declared the five
routes pointing at localhost, and `application-docker.yml` declared
**the same five routes** pointing at the Compose DNS names. The two
lists were identical in every field except for the hostnames.
Roughly twenty-five lines were duplicated per profile.

The implicit justification for this duplication was *"Spring Boot
activates the docker profile inside the container, and that profile
overrides the URIs"* — treating the override as a natural
per-environment tweak rather than as full structural replacement.

### How the problem surfaced

While adding a `CircuitBreaker` filter to the `/tasks/**` route in
`application.yml` (the base file only), the filter **did not apply**
when the application ran under the `docker` profile. Empirical
verification revealed it: requests to `/tasks/**` reached the task
service, but the breaker's `bufferedCalls` counter stayed at zero.
The filter was not seeing any of the traffic.

Introspection through `/actuator/gateway/routes` confirmed that the
routes effectively loaded under the docker profile **did not include
the filter**, even though `application.yml` declared it explicitly on
the corresponding route.

### Root cause

When Spring Boot merges YAML across profiles, **it replaces complete
lists in the configuration tree; it does not merge list elements one
by one**. When `application-docker.yml` redefines
`spring.cloud.gateway.server.webflux.routes` as a five-element list,
that list wholesale replaces the base list. Individual elements are
**not combined by `id`**. Any property present in the base list but
absent from the override (a `filters:` block added to a specific
route, for example) is silently dropped at profile activation time.

This behaviour is not a bug in Spring — it is consistent with the
"override is replacement" model for complex types. But it is a
classic source of multi-environment configuration bugs: **local CI
passes, target-environment runtime fails silently**. The bug is
particularly insidious because the diff between profiles looks
intentional and self-contained until the moment something in the
base file drifts away from what the override still says.

Constraints acknowledged for this decision:

- The gateway is the only module in the system that faces this
  structural duplication. Auth and task services have small
  per-profile overrides limited to actual environment differences
  (database URLs, Mongo URIs). They do not exhibit the same problem.
- The team is a single developer. Any pattern chosen must survive
  months of dormancy between edits without relying on institutional
  memory to "remember to keep both files in sync."
- The project targets consultancy and enterprise Java positions
  where multi-environment configuration is standard. The pattern
  should be recognisable to reviewers, not a bespoke invention.

## Decision

Refactor the gateway configuration so that **only the hostnames**
— the single dimension that legitimately varies per environment —
live in per-profile files. The **structure of the routes**
(predicates, filters, ids, ordering) lives **exactly once**, in the
base `application.yml`, and references the hostnames through Spring
placeholders (`${property.name}`).

Concretely:

- A new top-level `services:` block in `application.yml` declares
  two properties, `services.auth-uri` and `services.task-uri`, with
  the local development values as defaults.
- Every route in `application.yml` references these properties via
  `${services.auth-uri}` and `${services.task-uri}` instead of
  hard-coded URIs.
- `application-docker.yml` is stripped down to a `services:` block
  overriding those two properties with the internal Compose DNS
  names. It no longer contains any part of the `spring.cloud.gateway`
  tree.
- Any future environment (a hypothetical `application-staging.yml`,
  `application-prod.yml`) follows the same shape: three lines
  overriding `services.*`, nothing else.

### Alternatives considered

**Alternative A — Replicate the filter in `application-docker.yml`.**
The narrow fix to the immediate bug: add the `filters:` block to the
`task-service-route` in the docker override, mirroring the base.
Reasoned rejection: the fix does not address the structural cause.
The next time any change lands in a base route — a CORS filter, a
rate limiter, a header rewrite, an additional predicate — the same
silent drift will happen. The bug's category remains open;
Alternative A only closes a single instance of it. Worse, applying
this fix normalises the duplication as intentional and makes future
drifts easier to overlook.

**Alternative B — Placeholders with inline default values.**
Use the syntax `uri: ${services.task-uri:http://localhost:8082}`
without declaring the `services:` block in the base at all.
Reasoned rejection: this disperses configuration across the file.
The default value for a property lives at the point of use rather
than in a dedicated, self-documenting `services:` block. IntelliJ
loses the ability to autocomplete the property (there is no
declaration to autocomplete against), and a future
`additional-spring-configuration-metadata.json` entry has nothing
to attach to. The inline-default syntax is valid Spring, but it
optimises for terseness at the cost of discoverability.

**Alternative C — Keep the routes separate per endpoint after the
refactor, in case future filters need to diverge.**
Preserve `/tasks/**`, `/categories/**`, and `/me/activity/**` as
three separate route definitions in the base file even after
eliminating the duplication problem. Reasoned rejection: no evidence
justifies the divergence. The three routes point at the same bounded
context and share the same downstream health signal. Modelling them
as independent routes would suggest they can fail independently,
which is not the case at the infrastructure level. Applying the
same principle at the resilience layer, a per-endpoint breaker
would open and close out of sync with its siblings for no semantic
gain. If a legitimate need for per-resource filters emerges later,
splitting one unified route into three specialised ones is a five
minute refactor contained in a single file.

## Consequences

### Positive

- **Route structure lives in exactly one place.** Changes to
  predicates, filters, order, or identifiers apply to every profile
  automatically. Adding a new filter to the base file is now safe:
  it cannot be silently omitted from any environment because there
  is no per-environment copy to omit it from.
- **Per-profile files reflect only what actually varies.** The
  `application-docker.yml` file went from twenty-five lines
  duplicating structure to three lines overriding hostnames. The
  intent of the file is now legible at a glance: *"this is where
  we tell the gateway how to reach the neighbours in Compose."*
- **Scalable to additional environments.** A future
  `application-staging.yml` for a cloud deployment is three lines,
  not twenty-five. The scaling cost of adding an environment is
  linear in the number of things that actually differ (hostnames,
  credentials, feature flags), not in the size of the module's
  configuration.
- **Silent desynchronisation is structurally impossible.** The bug
  category that surfaced during the CircuitBreaker verification
  cannot recur in this form. It is not that the developer must
  remember to keep files in sync — it is that there is nothing to
  keep in sync.
- **The pattern reads as idiomatic Spring.** Placeholders with
  per-profile property overrides is a documented, widely-used
  approach in the Spring ecosystem. A reviewer sees a familiar
  shape, not a project-specific convention.

### Negative

- **One extra level of indirection when reading a route.** A reader
  who wants to know *"what URI does `task-service-route` point at
  under the docker profile?"* must resolve the placeholder mentally
  by looking up the value in `application-docker.yml`. This is a
  small, familiar cost for anyone working with Spring; it is called
  out here for completeness.
- **IntelliJ marks a "Scalar value is not allowed here" warning on
  the `services:` block.** The IDE validates YAML against the Spring
  Boot configuration schema and does not recognise
  project-defined properties. The warning is cosmetic and does not
  affect compilation, tests, or runtime. It is registered as
  technical debt to be resolved by declaring the properties in
  `META-INF/additional-spring-configuration-metadata.json`, which
  both silences the warning and documents the properties formally
  for downstream consumers of the artefact.
- **Tests instantiating the base configuration without an active
  profile use the local URIs.** This is correct for local tests and
  matches previous behaviour, but any test that needs to exercise
  the gateway against alternate hostnames must activate an
  appropriate profile explicitly.

### Neutral

- The decision commits to placeholder syntax as the mechanism for
  per-environment variation. If future configuration needs a
  fundamentally different variation model (feature flags conditioned
  on runtime discovery, for example), it will be introduced
  alongside placeholders rather than replacing them.

## Verification

The decision is verifiable operationally with the running compose
stack. From the repository root, after `docker compose up --build -d`:

**Route structure loaded under the docker profile includes the
filter defined in the base file:**

```bash
curl -s http://localhost:8080/actuator/gateway/routes | python3 -m json.tool
```

The response includes the unified `task-service-route` with
predicates `[/tasks/**, /categories/**, /me/activity/**]` and a
`CircuitBreaker` filter referencing `taskServiceBreaker` and
`forward:/fallback/tasks`. The URIs resolve to the Compose DNS
names (`http://task-service:8082`), confirming that the docker
profile applied the hostname override without erasing the filter.

**The refactor is exercised through a route that was not touched by
the original filter definition:**

```bash
docker compose stop task-service

TOKEN=$(curl -s -X POST http://localhost:8080/auth/login \
  -H "Content-Type: application/json" \
  -d '{"email":"alice@example.com","password":"Password123!"}' \
  | jq -r '.accessToken')

curl -s -w "\nHTTP %{http_code} — %{time_total}s\n" \
  -H "Authorization: Bearer $TOKEN" \
  http://localhost:8080/categories
```

The response is `HTTP 503` with the RFC 7807 `application/problem+json`
payload from the `/fallback/tasks` endpoint in approximately one
second (the first call after the breaker opens still incurs the real
downstream timeout; subsequent calls return in milliseconds).

The route being tested is `/categories/**`, which had no filter in
the original base file. Its coverage by the breaker demonstrates
that the unified `task-service-route` correctly serves all three
downstream paths under the docker profile.

**Reverting to a working baseline restores normal responses:**

```bash
docker compose start task-service
# wait for tmm-task-service to report healthy

curl -s -w "\nHTTP %{http_code} — %{time_total}s\n" \
  -H "Authorization: Bearer $TOKEN" \
  http://localhost:8080/categories
```

The response is `HTTP 200` with the paginated payload from
`task-service`, confirming that the placeholder-based configuration
resolves correctly to the internal Compose hostname under normal
operating conditions.

## Related decisions

- Border authentication at the gateway:
  [ADR-004](adr-004-border-authentication.md). The route structure
  refactored by this ADR is the same structure through which the
  gateway propagates the authenticated identity as `X-User-Id`.
- The `CircuitBreaker` filter whose silent absence exposed the
  duplication problem is documented in the module's README
  (Getting started section) and will be covered in a dedicated
  ADR-006 once the resilience module is complete (CircuitBreaker +
  TimeLimiter + Retry).
- Technical debt: declare `services.auth-uri` and `services.task-uri`
  in `api-gateway/src/main/resources/META-INF/additional-spring-configuration-metadata.json`
  to silence IntelliJ's schema validation and document the
  properties formally for tooling.
