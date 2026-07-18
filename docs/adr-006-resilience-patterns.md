# ADR-006: Resilience Patterns at the Gateway

## Status

Accepted — 2026-07-17.

## Context

The `api-gateway` module is the single entry point for all client
traffic to the microservices system. It routes requests to
`auth-service` and `task-service` on the internal Compose network,
propagates authenticated identity (see ADR-004), and returns
responses to callers. Its availability is therefore not an
implementation detail but a property of the system as a whole: if
the gateway becomes unresponsive, every downstream service becomes
effectively unreachable regardless of its own health.

The scenarios that threaten gateway availability are not primarily
those where a downstream service is cleanly down. A dead process
produces immediate `Connection Refused` responses at the TCP layer,
which the gateway observes in milliseconds and can react to
gracefully. The dangerous scenarios are the ones where a downstream
is **sick but not dead**: a saturated PostgreSQL under load, a JVM
in a long garbage-collection pause, a network path with dropped
packets and slow retransmits, a Mongo primary in the middle of a
failover election. In all of these, the TCP handshake with the
downstream completes normally, the request bytes arrive at its
kernel, but the response never arrives — or arrives after tens of
seconds — because the downstream process is stalled internally.

From the gateway's perspective these two situations look
identical at the network layer up to the moment a response is
expected. The distinction only becomes visible if the gateway
enforces an explicit ceiling on how long it will wait. Without
such a ceiling, the reactive request pipeline holds the
downstream connection, the caller thread, and the associated
memory attached to the operation until either the operating
system's stack timeout fires (typically tens of seconds) or the
caller gives up. Under concurrent load, this behaviour compounds:
one sick downstream turns healthy gateway capacity into a queue
of pending calls waiting on a service that will not answer. The
gateway becomes unable to serve traffic to healthy neighbours
because its resources are exhausted holding requests to the sick
one. This is the **cascading failure** pattern documented in the
resilience literature since at least the Netflix Hystrix era; it
was the original motivation for the Circuit Breaker pattern
itself.

No single resilience mechanism eliminates the risk. A CircuitBreaker
alone identifies a downstream as unhealthy after enough failures
accumulate, but if each failure takes tens of seconds to observe,
the breaker opens too late — the damage to gateway resources is
already done by the time the statistical threshold trips. A
Retry alone amortises transient network blips of a few hundred
milliseconds, but against a genuinely dead downstream it multiplies
the effective traffic load rather than reducing it, becoming an
internal denial-of-service. A response-time ceiling alone protects
individual requests but rejects transient blips as if they were
permanent failures, generating spurious 503s to callers when a
single retry would have succeeded. The three mechanisms are
complementary: the timeout gives the breaker something observable
to count quickly, the breaker prevents the retry from becoming an
amplifier of downstream distress, the retry absorbs the blips that
would otherwise pollute the breaker's statistical window and open
it spuriously.

### How the problem surfaced

The naive implementation of this ADR's concerns proceeded
incrementally across three sessions: CircuitBreaker (documented in
ADR-005 as the trigger for the config refactor), Retry, and finally
TimeLimiter. During the third session an empirical anomaly appeared
that reshaped the understanding of the whole composition.

With `task-service` paused via `docker compose pause` — chosen
because it emulates a sick-but-not-dead downstream more faithfully
than `docker compose stop`, since the kernel of the paused
container continues to accept TCP connections while the Java
process is frozen and never reads them — the observed latency for
a curl to `/tasks` was **consistently ~1020 ms**, independent of
whether Retry was configured or not, independent of whether
`metadata.response-timeout` was declared on the route or not, and
independent of the specific values used for those parameters.

The predicted latency, based on the documented behaviour of the
individual filters, was in the range of **30 seconds to
indefinite**: without a configured timeout on the downstream HTTP
client, a paused peer should keep the gateway waiting until either
Reactor Netty's own connection timeout (typically 30 seconds) or
the OS-level stack timeout intervened. The empirical measurement
did not match this prediction by more than an order of magnitude.

### Root cause

The `spring-cloud-starter-circuitbreaker-reactor-resilience4j`
dependency — the mandatory abstraction for the declarative
`CircuitBreaker` filter in Spring Cloud Gateway — **auto-configures
a TimeLimiter alongside every CircuitBreaker instance**, with a
default `timeoutDuration` of one second inherited from
`io.github.resilience4j.timelimiter.TimeLimiterConfig#ofDefaults()`.
This TimeLimiter is not a separate filter that a developer opts
into; it is embedded inside the CircuitBreaker abstraction, wraps
the entire operation the CircuitBreaker protects (including any
Retry logic nested within it), and applies its timeout regardless
of any downstream-client timeout configuration.

Empirical verification made the mechanism visible. With the
integrated default of one second, the observed latency was
~1020 ms. With the TimeLimiter reconfigured explicitly to five
seconds via `resilience4j.timelimiter.instances.taskServiceBreaker`,
the observed latency became **~5020 ms** — precisely the new
ceiling. The default was neither hypothetical nor dormant: it had
been the dominant timeout mechanism from the moment the
CircuitBreaker starter was added to the classpath, invisibly, with
no configuration file mentioning it.

A separate empirical finding confirmed the same session: the
per-route `metadata.response-timeout` and `metadata.connect-timeout`
declarations documented in the Spring Cloud Gateway reference for
per-route timeout configuration **have no observable effect** under
the new `spring.cloud.gateway.server.webflux.routes` prefix in
Spring Cloud Gateway 2025.0.0. Values declared as integers, visible
through `/actuator/gateway/routedefinitions`, do not translate into
timeout enforcement at runtime. The effective timeout in this
topology is exclusively the one imposed by the integrated
TimeLimiter of the CircuitBreaker abstraction.

The consequence is architectural, not merely operational: the
resilience composition in this system is a **four-layer nesting**
rather than the three-layer one the naive reading of the
independent filters would suggest. From the outside in: the
TimeLimiter enforces the total ceiling on the protected operation;
the CircuitBreaker decides whether to admit the operation at all,
based on its statistical window; the Retry attempts the individual
call multiple times with backoff if failures are transient; the
raw HTTP call reaches the downstream. Every operation the gateway
routes through the protected task-service path traverses all four
layers, and any budgeting decision about latency, backoff, or
retry counts must be made against the outermost layer as the
binding constraint.

Constraints acknowledged for this decision:

- The gateway is reactive (Netty), not servlet. Bulkhead patterns
  that isolate thread pools do not translate directly; recovery
  paths depend on Reactor's cancellation semantics rather than on
  thread interruption.
- The system has a single developer and no dedicated operations
  team. The resilience configuration must remain readable months
  after it was written, without ambient institutional memory.
- The target audience for the portfolio (consultancy, traditional
  banking, insurance, public sector) expects standard patterns
  recognisable to reviewers. Idiomatic Resilience4j configuration
  in `application.yml` fits that expectation; bespoke Java
  customisers or programmatic route locators do not.
- No chaos engineering infrastructure is available. Empirical
  verification uses `docker compose pause` to emulate a sick
  downstream and observes behaviour through actuator endpoints
  and `curl` timing.

## Decision

Compose CircuitBreaker, Retry, and TimeLimiter at the gateway as a
single unified resilience policy for the `task-service-route`.
Configure each pattern with values that make its role in the
composition legible from the configuration file alone, and choose
values that make the outermost layer (TimeLimiter) the binding
constraint on total latency.

Concretely:

- The **CircuitBreaker** instance `taskServiceBreaker` is
  configured with a count-based sliding window of ten calls, a
  minimum of five calls before evaluation, a failure rate threshold
  of fifty percent, a wait duration in OPEN state of ten seconds,
  and three permitted calls in HALF_OPEN. These values are
  didactic: they permit visible state transitions within a single
  interactive session. In a production topology with hundreds of
  requests per second per route, the window would be larger and
  the wait duration longer, but the parameters would occupy the
  same conceptual slots.
- The **Retry** filter is scoped to `GET` requests only, on the
  `SERVER_ERROR` (5xx) series, with two retries and an exponential
  backoff of `firstBackoff=500ms`, `maxBackoff=1500ms`, `factor=2`,
  `basedOnPreviousValue=false`. GET-only scoping reflects RFC 9110
  idempotency: retrying a non-idempotent verb without an
  `Idempotency-Key` protocol risks producing duplicate side effects
  when a request succeeded at the downstream but the response was
  lost in transit. The `maxBackoff` ceiling is a defensive
  guardrail that does not bind at the current retry count but
  bounds future increases.
- The **TimeLimiter** instance `taskServiceBreaker` (identically
  named to the CircuitBreaker instance, so that Spring Cloud
  CircuitBreaker's abstraction associates them automatically) is
  configured with `timeoutDuration=5s` and `cancelRunningFuture=
  true`. Five seconds is deliberately longer than the theoretical
  worst case of Retry with per-call timeouts (which would be
  4.5 seconds: 2s + 500ms backoff + 2s) — the TimeLimiter acts as
  a safety net around the entire protected operation, not as the
  primary timeout per call. In the current topology no primary
  per-call timeout is effective (see Alternative B below), so in
  practice the TimeLimiter is the sole timeout mechanism, and its
  value bounds the total user-visible latency directly.
- The **order in the filter chain** is CircuitBreaker (outermost)
  then Retry (innermost), matching the argumentation established in
  the Retry session: with CircuitBreaker outside, an OPEN state
  short-circuits before Retry can amplify traffic against an
  already-diagnosed sick downstream; with CircuitBreaker outside,
  a transient blip that Retry successfully absorbs registers as a
  single successful call at the breaker, preventing the blip from
  contaminating the statistical window. The TimeLimiter sits above
  the whole composition as a consequence of being integrated into
  the CircuitBreaker abstraction, not as a filter-chain choice.

### Alternatives considered

**Alternative A — CircuitBreaker alone, no Retry, no explicit
TimeLimiter.** The minimal resilience configuration: rely on the
breaker to open after enough downstream failures and on the
integrated one-second default TimeLimiter to bound individual
calls. Reasoned rejection: the one-second default is invisible in
configuration and therefore not auditable; a future maintainer
would have no textual evidence that a timeout exists at all. More
importantly, without Retry, transient blips of a few hundred
milliseconds become 503s to the caller when a single retry with
backoff would have succeeded. The cost of adding Retry (a few
lines of YAML and a well-understood configuration surface) is far
lower than the cost of the false positives it prevents.

**Alternative B — Per-route metadata timeouts
(`metadata.response-timeout`, `metadata.connect-timeout`).** The
mechanism documented in the Spring Cloud Gateway reference for
declaring timeouts on individual routes without programmatic
configuration. Reasoned rejection: verified empirically not to
have any effect under the new
`spring.cloud.gateway.server.webflux.routes` prefix in Spring
Cloud Gateway 2025.0.0. The declarations are read by
`RouteDefinitionRouteLocator` and visible through
`/actuator/gateway/routedefinitions` as integer values, but the
downstream HTTP client does not consult them at request time. The
declarations are preserved in the configuration file with an
explanatory comment as documentation of the finding, pointing to
this ADR; they do not participate in the effective policy. This
alternative could not be relied on regardless of merit, because
the mechanism does not function in the current framework version.

**Alternative C — Bulkhead pattern
(`spring-cloud-starter-circuitbreaker-reactor-resilience4j`
supports it via the Resilience4j Bulkhead module).** Isolate the
resources used by different downstream calls into separate
semaphore-bounded or threadpool-bounded compartments, so that
saturation of one downstream cannot exhaust the resources used to
call the others. Reasoned rejection: the pattern targets systems
where a single application makes calls to many downstreams in
parallel and a slow one can starve the fast ones. In a gateway
whose sole responsibility per request is to relay one call to one
downstream, and where the reactive stack does not use thread pools
in the classical sense, the resource-isolation benefit is
marginal. The costs — additional configuration surface, additional
conceptual load for future maintainers, additional actuator noise
— are not justified. The Bulkhead pattern would become relevant
in a future evolution of the gateway that fans out requests to
multiple downstreams in parallel (a compose-and-forward endpoint,
for example), at which point a dedicated ADR would document the
change.

**Alternative D — Increase Retry aggressiveness (three or four
retries, longer backoff).** More aggressive retry policies improve
resilience to intermittent failures at the cost of amplifying
traffic against genuinely unhealthy downstreams and increasing
worst-case latency observed by callers. Reasoned rejection: with
two retries and a 500ms/1s backoff schedule, the theoretical worst
case of Retry alone is 4.5 seconds, well under the TimeLimiter
ceiling of 5s. Additional retries would not increase the observed
worst case (bounded by the outer TimeLimiter regardless) but would
cause the operation to spend more of that time attempting downstream
calls, adding load to a service already exhibiting failure. The
current choice keeps the retry budget modest and lets the
CircuitBreaker take over as the primary mitigation once the failure
signal is statistically clear.

## Consequences

### Positive

- **Total latency is bounded by an auditable value.** The 5s
  TimeLimiter ceiling is declared in `application.yml` at the
  path `resilience4j.timelimiter.instances.taskServiceBreaker.
  timeoutDuration`. Any future review of the gateway's SLA
  contract can point at that line as the answer to *"what is the
  maximum time a client will wait for a task-service call?"*.
  Before this ADR, the answer was the invisible one-second
  default of the integrated TimeLimiter, which a reviewer would
  have to derive from the Spring Cloud CircuitBreaker source
  code.
- **Transient blips do not propagate to callers.** With Retry
  configured on GET / 5xx / IOException / TimeoutException, a
  brief downstream hiccup — a 300ms GC pause, a momentary Postgres
  slowdown — is absorbed by the retry with backoff and only the
  successful result is returned. The caller sees a slightly
  longer response time, not an error.
- **Genuine downstream failures produce fast, cheap fallback
  responses once the breaker opens.** Empirical verification of
  the OPEN transition showed a latency discontinuity of three
  orders of magnitude: from ~5000ms per call (TimeLimiter-bound)
  to ~5ms per call (breaker short-circuit). Under concurrent load
  this discontinuity is the primary protection against gateway
  resource exhaustion: while the breaker is OPEN, all callers to
  the sick downstream receive their 503 in milliseconds, freeing
  gateway resources to serve traffic to healthy neighbours.
- **The composition is legible from configuration alone.** The
  three patterns and their parameters live in a single
  `application.yml`, at conventional paths recognisable to any
  Spring engineer. A new maintainer can understand the policy by
  reading the file top to bottom without needing to consult
  external documentation or Java customisers.
- **The choice of GET-only Retry is defensible from first
  principles.** The RFC 9110 idempotency argument is one
  paragraph of explanation and generalises to any HTTP-based
  system. Extending Retry to POST later would require introducing
  an `Idempotency-Key` protocol between callers and downstreams,
  a scope decision explicit enough to justify its own future ADR
  rather than a silent configuration change.

### Negative

- **The worst-case pre-open latency is 5s.** Before the breaker
  accumulates enough failures to open, each individual request
  against a sick downstream consumes the full TimeLimiter budget.
  For a system in genuine distress this means callers experience
  5 seconds of latency per request until the breaker trips. The
  didactic parameter values in this project make the transition
  fast (five failed calls in a ten-call window is enough); a
  production configuration with larger windows and more
  conservative thresholds would extend the vulnerable period.
- **Conceptual complexity increases nontrivially.** The composition
  is a four-layer nesting where the outermost layer (TimeLimiter)
  is invisible in the filter chain and lives in a different
  section of the configuration file (under `resilience4j`, not
  under `spring.cloud.gateway`). Explaining the request lifecycle
  now requires four levels rather than three. This is a documented
  and unavoidable characteristic of the Spring Cloud CircuitBreaker
  abstraction, not a choice; but it is a cost paid by every
  contributor who has to reason about latency in the future.
- **The per-route `metadata` mechanism becomes documentation
  debt.** The `metadata.response-timeout` and
  `metadata.connect-timeout` declarations remain in the route
  configuration with an explanatory comment, because removing
  them would erase the empirical finding that they do not
  function. Their presence is misleading to a reader who has not
  read this ADR, and their absence would be misleading to a
  future maintainer who reintroduces them without knowing why.
  The comment mitigates both, but the tension is real.

### Neutral

- **The 5s TimeLimiter is longer than the theoretical Retry worst
  case (4.5s).** In the current topology, where the per-call
  timeout is not effective, this margin is unused: the TimeLimiter
  simply bounds the total. If the per-call timeout mechanism
  becomes effective in a future framework version, or if a
  programmatic route locator is introduced to enforce per-call
  timeouts, the 5s ceiling will become a genuine safety net around
  the 4.5s Retry ceiling, and the naming *"outer limit"* will
  become semantically accurate. Until then, it is a value with two
  possible readings and the same numeric expression.
- **The composition does not include Bulkhead.** This is a
  positive decision (see Alternative C), not an oversight. The
  absence is documented so that a future evolution introducing
  fan-out routing can revisit it explicitly.

## Verification

The decision is verifiable operationally with the running compose
stack. All commands assume `docker compose up --build -d` completed
and all five containers are healthy.

**The TimeLimiter is loaded with the configured value:**

```bash
curl -s http://localhost:8080/actuator/gateway/routes \
  | python3 -m json.tool \
  | grep -A 5 taskServiceBreaker

curl -s http://localhost:8080/actuator/circuitbreakers \
  | jq .circuitBreakers.taskServiceBreaker
```

The route definition includes the CircuitBreaker filter
(`order = 1`) referencing `taskServiceBreaker` and the Retry
filter (`order = 2`) with the retry parameters. The circuit
breaker actuator reports the instance in state `CLOSED` at the
start of the session.

**The healthy path is not affected by the TimeLimiter ceiling:**

```bash
TOKEN=$(curl -s -X POST http://localhost:8080/auth/login \
  -H "Content-Type: application/json" \
  -d '{"email":"alice@example.com","password":"Password123!"}' \
  | jq -r '.accessToken')

curl -s -o /dev/null -w "%{http_code} — %{time_total}s\n" \
  -H "Authorization: Bearer $TOKEN" \
  http://localhost:8080/tasks
```

Observed: `200 — 0.264s`. Well below the 5s ceiling, well below
any of the Retry backoffs. The resilience policy imposes no
detectable cost on healthy operation.

**A sick downstream is bounded by the TimeLimiter:**

```bash
docker compose pause task-service

curl -s -o /dev/null -w "%{http_code} — %{time_total}s\n" \
  --max-time 15 \
  -H "Authorization: Bearer $TOKEN" \
  http://localhost:8080/tasks
```

Observed: `503 — 5.017s`. The response payload is the RFC 7807
`application/problem+json` document produced by the
`FallbackController`. The measurement is consistent across
repetitions (5.006s, 5.020s in immediate follow-up runs) and
matches the configured `timeoutDuration=5s` within measurement
noise. The Retry filter's individual attempts do not observably
bound the operation, confirming that the per-call
`metadata.response-timeout` declaration has no effect under the
current framework version.

**The CircuitBreaker opens after the statistical threshold:**

With `task-service` still paused, four consecutive curl
invocations produce:

```
call 1: HTTP 503 — 5.018072s
call 2: HTTP 503 — 5.006243s
call 3: HTTP 503 — 5.020617s
call 4: HTTP 503 — 0.004689s
```

The discontinuity between call 3 and call 4 marks the transition
CLOSED → OPEN. After three failed calls, combined with the two
recorded before the batch, the sliding window contains five calls
and four failures, exceeding the `failureRateThreshold` of 50%.
The immediately following call is short-circuited by the OPEN
breaker: it does not attempt the downstream and the fallback
responds in under five milliseconds. Actuator inspection confirms
the state transition:

```json
{
    "failureRate": "80.0%",
    "bufferedCalls": 5,
    "failedCalls": 4,
    "notPermittedCalls": 1,
    "state": "OPEN"
}
```

The `notPermittedCalls` counter increments on each subsequent
request while OPEN, distinguishing calls the breaker refused to
attempt from calls it attempted and observed to fail.

**Recovery to CLOSED after `waitDurationInOpenState`:** This
transition was verified in Sesión 8 with the earlier CircuitBreaker
scaffolding and is not re-verified here. The mechanism is
unchanged: after ten seconds in OPEN the breaker transitions to
HALF_OPEN, admits three probe calls, and returns to CLOSED if they
succeed against a recovered downstream.

## Related decisions

- Border authentication at the gateway:
  [ADR-004](adr-004-border-authentication.md). The
  `task-service-route` protected by this ADR is the same route
  through which the gateway propagates the authenticated identity
  as `X-User-Id`. The resilience patterns operate downstream of
  the authentication filter, so failures observed by the breaker
  are failures of authenticated requests reaching the downstream,
  not authentication failures at the gateway itself.
- Configuration externalisation:
  [ADR-005](adr-005-config-externalization-by-profile.md). The
  route structure carrying this ADR's filter chain is declared
  exactly once in `application.yml` and referenced through
  placeholders resolved per profile. Adding a `CircuitBreaker`,
  `Retry`, or `metadata` block to the route is safe from the
  silent drop-on-profile-override problem that motivated ADR-005.
- Technical debt recorded and deferred: confirm the exact semantics
  of `retries: N` in Spring Cloud Gateway 2025.0.0 (whether it
  means "N total attempts including the original" or "N additional
  attempts after the original") through a controlled experiment
  with `retries: 3` and observation of the timing shift. The
  empirical measurement in Sesión 9 suggested the former; a formal
  confirmation would strengthen the decision record but is not
  operationally blocking.
- Technical debt recorded and deferred: investigate whether the
  `metadata.response-timeout` and `metadata.connect-timeout`
  mechanisms are intended to function under the
  `spring.cloud.gateway.server.webflux` prefix in Spring Cloud
  Gateway 2025.0.0 and, if so, why they do not in this project.
  A minimal reproduction against a fresh Spring Boot 3.5.4 +
  Spring Cloud 2025.0.0 project would clarify whether the finding
  is a framework issue, a project-specific misconfiguration, or a
  documentation gap.
