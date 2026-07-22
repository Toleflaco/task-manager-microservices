# ADR-007: Kafka event bus for task lifecycle events

## Status

Accepted — 22 July 2026.

Supersedes the synchronous `ActivityEventListener` pattern established for the activity log in Phase 6.5 (see ADR-001). Complements ADR-004 (border authentication) and ADR-005 (profile-specific configuration).

## Context

The `task-manager-api` monolith wrote activity log entries synchronously in the same JPA transaction as the domain change, via an `@EventListener` on Spring `ApplicationEvent`s. This coupling was acceptable within a single service but became structurally incompatible with the microservices split of Phase 10:

- **Coupling by shared transaction.** A synchronous listener running inside the same transaction ties the durability of the domain change to the durability of the audit write. If the audit persistence path breaks, the domain transaction rolls back — a downstream concern silently vetoes upstream progress.

- **No fan-out.** Any future reactive concern on task events (email notifications, search index updates, webhooks, analytics ingestion) would require adding another `@EventListener` in the same JVM. Every concern would share the same lifecycle, the same failure modes, and the same deployment cadence.

- **No extraction path.** Extracting the activity log to a dedicated `activity-service` would require inventing an integration mechanism at extraction time. Deferring that decision to the extraction moment forces re-architecture under time pressure.

- **Cross-service audit collection.** The activity log needs to capture events from multiple future services (task-service today, notification-service tomorrow, whatever). A synchronous per-service listener does not compose.

The system already runs three Spring Boot services (`api-gateway`, `auth-service`, `task-service`) behind a border-authenticating gateway. Postgres provides transactional persistence for tasks and categories; MongoDB Atlas M0 provides the flexible-schema store for the activity log (see ADR-001). What is missing is an asynchronous decoupling mechanism between domain writes and downstream reactive concerns.

Three broad categories of solution exist:

1. **In-process asynchronous events.** Keep the `@EventListener` pattern but make it async (`@Async`). Reduces coupling in time but not in space — still same JVM, still no fan-out to other services.
2. **Direct HTTP calls to downstream services.** Task-service calls activity-service directly on each domain event. Introduces synchronous inter-service coupling; task-service must know about every downstream concern; failures of downstream services impact task-service availability.
3. **Message broker.** Publish domain events to a broker; downstream services consume independently. Decouples in time, space, and failure. Adds infrastructure and operational complexity.

The message broker approach fits the trajectory of the codebase (already microservices, already using Docker Compose for orchestration, already accustomed to Testcontainers-based integration testing) and matches industry practice at the kinds of employers targeted (banca tech, consultoras, grandes empresas). Kafka specifically is the de facto standard in the JVM ecosystem for this workload.

## Decision

Adopt Apache Kafka as the event bus for domain lifecycle events emitted from `task-service`, with the specific configuration described below. The activity log becomes a Kafka consumer within `task-service` (temporarily co-located pending extraction to a dedicated `activity-service`).

### D1 — Topology of topics

Two topics: `task.events` and `category.events`. Each topic carries a `type` discriminator field in the payload (`CREATED`, `UPDATED`, `STATUS_CHANGED`, `DELETED` for tasks; `CREATED`, `UPDATED`, `DELETED` for categories). Rejected alternatives: a single generic `domain.events` topic (harder to schema-evolve independently, forces every consumer to filter), and seven type-specific topics (unnecessary granularity given consumers typically care about all events of a resource type).

### D2 — Partitions

Three partitions per topic at creation time. Kafka partitions cannot be shrunk without recreating the topic and cannot be expanded without breaking cross-partition ordering guarantees retrospectively. Three is chosen as a compromise between over-provisioning (waste at the current scale of one small user base) and under-provisioning (single-partition bottleneck under any real load). Revisit if the topic sees sustained multi-thousand-events-per-second traffic (not projected).

### D3 — Message key

`userId` (as `String`) for both topics. Consequences:

- Events for the same user land in the same partition, giving per-user ordering guarantees for downstream consumers.
- Aligns with the identity axis established in ADR-004 (border-authenticated `X-User-Id` header) and with the `/me/activity` read-side pattern.
- Downstream reactive concerns (per-user email digests, per-user webhooks) can shard cleanly by consuming assigned partitions.
- Trade-off: a single high-activity user occupies one partition disproportionately. Acceptable at current scale; not a bottleneck in projected usage.

### D4 — Initial consumer groups

One consumer group at rollout: `activity-log-writer`. Every future reactive concern (email notifications, search index, webhooks) becomes a new consumer group with its own offset tracking. The two-topic topology combined with per-group offsets makes this additive: adding a concern never affects existing ones.

### D5 — Commit strategy: at-least-once via manual acknowledgement, backed by indefinite exponential retry

Consumer configuration:

- `enable-auto-commit: false`
- `ack-mode: MANUAL_IMMEDIATE`
- `auto-offset-reset: earliest`

Consumer code path: `mongoTemplate.insert(doc)` first, `ack.acknowledge()` second. The offset commits only after Mongo confirms the write.

**Retry policy: `ExponentialBackOff(initial=1s, multiplier=2.0, maxInterval=30s)` with no total elapsed time cap.** Retries are effectively indefinite: the consumer blocks on a failing partition until the downstream dependency (Mongo) recovers.

This retry policy is not the framework default and is explicitly configured in `KafkaConfig.java` as a `DefaultErrorHandler` bean. Rationale is documented in Consequences C1 below — the Spring Kafka default (`FixedBackOff(0ms, 9 attempts)`) silently discards messages after the retry budget is exhausted, breaking at-least-once when downstream outages exceed roughly 5 minutes.

### D6 — Idempotency at the consumer: `_id = eventId`

Each event carries a `eventId` (UUID) generated by the publisher. The consumer uses this UUID as the `_id` of the `ActivityEvent` document in MongoDB.

Persistence pattern: `mongoTemplate.insert(doc)` inside a `try { ... } catch (DuplicateKeyException) { log.info("Duplicate skipped"); }`.

Effect: processing the same event N times produces exactly one document. Combined with D5 (at-least-once delivery), this yields the "effectively exactly-once" pattern from the consumer's perspective — Kafka may deliver the message more than once (during retries, offset resets, or consumer restarts mid-processing), but the persisted state is idempotent.

This idempotency is bidirectional (see Consequence C4 for the empirical observation from session 15 block 6.3): it prevents duplicates on normal reprocessing, and it makes offset resets a safe operational tool for recovering events that were lost due to bugs while they still exist in the topic.

### D7 — Migration from `@EventListener` to `@KafkaListener`

Complete migration, no coexistence. The legacy `ActivityEventListener` was removed in the same branch as the Kafka integration (commit `8bb1206`). During development (sessions 12-14), both listeners coexisted temporarily and wrote duplicate documents; this state was explicitly transient and closed at end of session 14.

Reasoning: dual writes during migration served verification, but leaving them in production would double the write volume without benefit. The activity log post-migration writes only via the Kafka consumer.

### D8 — Location of publishers

Dedicated Spring components (`TaskEventKafkaPublisher`, `CategoryEventKafkaPublisher`) rather than adding methods to the existing `ActivityEventListener`. Rejected alternative: cluttering an already-doing-too-much class with additional responsibilities. Publishers own only the Kafka publishing concern.

Each publisher listens to the local Spring `ApplicationEvent`s via `@TransactionalEventListener(phase = AFTER_COMMIT)` and translates them to Kafka messages via `KafkaTemplate.send(...)`.

### D9 — Synchronization with the JPA transaction: `@TransactionalEventListener(phase = AFTER_COMMIT)`

Events are published to Kafka only after the originating JPA transaction commits successfully. Events from rolled-back transactions are never published, preventing observers from reacting to state that never existed.

This does **not** cover the case where the JPA transaction commits and then the Kafka publish fails (broker unavailable at that specific moment). That gap is closed only by the transactional outbox pattern, which is not implemented in this ADR — see the Deuda técnica section.

### D10 — Thin events

Payload of each event contains only: `eventId`, `type`, `userId`, resource ID (`taskId` or `categoryId`), `timestamp`. No business fields (no `title`, `status`, `oldStatus`, `newStatus`, `name`, etc.).

Consequences: the activity log post-migration loses business detail for five of seven event types (Task CREATED, DELETED, STATUS_CHANGED; Category CREATED, DELETED). Task UPDATED and Category UPDATED were already field-less in the legacy listener, so no regression there.

Rationale:

- **Every field in a fat event is a backward-compatibility commitment forever.** Consumers may depend on any published field; removing or renaming becomes a breaking change coordinated across services.
- **Thin events reflect what happened, not the state of the thing.** Consumers that need current state can look it up from the source of truth (task-service's Postgres) via the resource ID.
- **Preparation for extraction.** When the activity log consumer moves to a dedicated `activity-service`, that service will not have direct access to task-service's Postgres. Thin events make that extraction transparent — nothing changes at extraction time; the consumer already carries no coupling to task-service internals.

Option 2b — enriching only STATUS_CHANGED events with `oldStatus`/`newStatus` — was considered mid-implementation and rejected. See Alternatives Considered.

## Consequences

### C1 — The retry policy of the listener is part of the at-least-once guarantee (not an implementation detail)

The commit-manual-after-write pattern (D5) is necessary but not sufficient for at-least-once delivery. It only works if retries are effective until success.

**Empirical finding from session 15 block 6.1**: with Spring Kafka's `DefaultErrorHandler` default (`FixedBackOff(interval=0ms, maxAttempts=9)`), during a Mongo outage of ~17 minutes, only one of three events published was persisted. The other two were silently discarded: the error handler exhausted its 10 retry budget (each retry consuming ~30s of `MongoTimeoutException`), then advanced the offset without a Mongo write.

Log excerpt:

```
2026-07-22T05:05:05.370Z ERROR DefaultErrorHandler:
  Backoff FixedBackOff{interval=0, currentAttempts=10, maxAttempts=9} exhausted for task.events-2@7
```

The framework's error message calls this "exhausted" — accurate but understated. Operationally, this is silent data loss.

**Correction**: `ExponentialBackOff(1000L, 2.0)` with `setMaxInterval(30_000L)` and no `setMaxElapsedTime` call. Retries indefinite, wait growing 1s → 2s → 4s → 8s → 16s → 30s → 30s → 30s… A consumer blocks on the failing partition until the downstream recovers.

Consumer lag becomes the visible signal of a stuck partition (monitored via `kafka-consumer-groups.sh --describe` in dev, via metrics/alerts in production). This replaces the invisible failure mode of the default configuration: a growing lag is an incident someone will notice; a silently dropped message is a data loss no one notices until an audit.

The design decision to prefer a stuck consumer to silent loss is deliberate: **an incident visible in lag is better than data loss invisible in dashboards.**

### C2 — Framework defaults must be made explicit or replaced

`DefaultErrorHandler` with its `FixedBackOff(0, 9)` default is correct for many use cases (poison messages that will never succeed, deserialization failures, business bugs that require code changes). It is incorrect for the case of temporarily unavailable downstream dependencies.

The lesson generalizes beyond Spring Kafka: any framework's default behavior encodes assumptions about failure modes. When your architecture makes different assumptions, you must either verify the default happens to match, or override it explicitly with reasoning documented in an ADR.

### C3 — Business detail is lost from the activity log for five of seven event types

Enumerated for clarity:

| Event type              | Pre-migration listener       | Post-migration Kafka consumer |
|-------------------------|------------------------------|-------------------------------|
| TASK_CREATED            | before={}, after={title, ...} | before={}, after={}           |
| TASK_UPDATED            | before={}, after={}          | before={}, after={}           |
| TASK_STATUS_CHANGED     | before={status}, after={status} | before={}, after={}        |
| TASK_DELETED            | before={title, ...}, after={} | before={}, after={}          |
| CATEGORY_CREATED        | before={}, after={name}      | before={}, after={}           |
| CATEGORY_UPDATED        | before={}, after={}          | before={}, after={}           |
| CATEGORY_DELETED        | before={name}, after={}      | before={}, after={}           |

Explicit trade-off:

- The activity log becomes a "what happened when to whom" record, not a "what changed exactly" record.
- Users viewing `/me/activity` see a chronological list of actions on their resources by ID and type, without the resource content at the time of the event.
- Anyone needing "what was the title of task X on this date" must query task-service and rely on its own history (JPA auditing on the entity itself, out of scope for this ADR).

Accepted as the cost of D10 (thin events) and preparation for extraction. If a business requirement emerges for richer audit content, revisit via a new ADR — options include enriching only specific event types with limited business fields (see Alternative A5), or building a separate CQRS-style projection service that maintains a full-history view by combining Kafka events with task-service queries.

### C4 — Idempotency by `_id = eventId` is bidirectional

**Empirical finding from session 15 block 6.3**: after a consumer group offset reset to `earliest`, the consumer reprocessed all 15 messages in the topic. 13 messages already had a corresponding document in Mongo — their re-insert raised `DuplicateKeyException` and was silently caught, offset advanced, no document created. Zero duplicates.

Bonus finding: 2 messages did NOT have a corresponding document in Mongo (the two lost in the first-pass C1 bug before the fix). Their re-insert succeeded. **The reset recovered the events lost by the earlier bug, using only mechanisms already present in the design.**

This is not a design goal — the primary purpose of D6 is to prevent duplicates from normal reprocessing. But it emerges as a valuable operational property: **when you discover retrospectively that events were lost, and the messages still exist in the topic, a controlled consumer group reset will recover them safely without duplicating what was already processed.** Without idempotency, a reset would multiply everything.

Retention of messages in the topic is therefore a form of operational insurance. Default Kafka retention is 7 days; extending this for topics containing audit-relevant events may be worth revisiting (see Deuda técnica).

### C5 — The consumer group is a contract between broker and consumer

The offsets committed by the consumer live in Kafka (specifically in the internal `__consumer_offsets` topic), not in the consumer's memory. Consequences:

- Consumer restarts are seamless: the consumer resumes from the last committed offset, not from `earliest` or `latest`. Verified empirically in session 15 block 6.2.
- If the consumer JVM crashes mid-processing (between `insert` success and `ack.acknowledge`), the next start reprocesses that message — caught by idempotency (D6).
- Multiple consumer instances in the same group share partitions cooperatively; Kafka rebalances assignment automatically.
- Adding a new consumer group to a topic later reads from `earliest` by default — the audit log started with `earliest` deliberately during rollout to capture all historical events already published.

### C6 — Consumer bottleneck under per-partition failure

Because message key is `userId` (D3), all events for a given user land in the same partition. If the consumer gets stuck retrying a message for user U (due to a persistent downstream failure specific to that message), the consumer cannot advance to messages for user V that were published later to the same partition.

This is a deliberate trade-off: preserving per-user ordering requires per-user partition affinity, which requires per-user head-of-line blocking on failures.

Mitigations available if this becomes a problem:

- More partitions (spreads load, dilutes head-of-line effect across more consumers).
- Selective retry with dead-letter topic for poison messages (adds infrastructure, see Alternatives A1).
- Per-message retry limits with escalation (breaks the "no silent loss" guarantee, would require reworking C1).

None applied today; monitor.

### C7 — Operational infrastructure required

Adopting Kafka is not free. What now exists in the operational surface:

- Kafka broker in Docker Compose (single-node, KRaft combined mode, `apache/kafka:3.7.0`). Volume `kafka-data` for durability across Compose restarts.
- Two client listeners: `INTERNAL://kafka:9092` for compose-internal traffic, `EXTERNAL://localhost:9094` for host-side tooling (`kcat`, `kafka-console-consumer.sh`, etc.).
- Cascading healthchecks in Docker Compose to ensure ordering during `up`.
- New failure modes to understand: consumer lag, offset commits, rebalances, retention.
- New operational tools: `kafka-consumer-groups.sh` for observing/manipulating offsets, `kafka-console-consumer.sh` for inspecting messages, `kafka-console-producer.sh` for manually publishing test messages.

Team members (currently one) must learn the operational vocabulary. Documented as "necessary skill" for the roadmap — this is directly employable knowledge.

### C8 — Preparation for extraction to `activity-service`

The consumer currently lives inside `task-service` for pragmatic reasons (fewer services during Phase 10 rollout). It writes to a MongoDB Atlas instance that also serves the read-side (`/me/activity` endpoint).

The design contains no coupling between the write path (Kafka consumer) and the read path (JPA/Spring Data MongoDB repository) beyond sharing the same collection. When extraction to a dedicated `activity-service` becomes worthwhile (multiple services publishing events, or the audit log outgrowing Mongo Atlas M0), the extraction is mechanical: move the consumer + MongoDB config to the new service, update Docker Compose, done. No API changes, no coordination window.

Estimated effort at time of extraction: one afternoon. Not scheduled; move only when driven by concrete need.

## Alternatives considered

### A1 — Dead-letter topic (DLT) instead of indefinite retry

**Considered:** on retry exhaustion, publish the failed message to a dead-letter topic for out-of-band inspection and replay. Standard Spring Kafka pattern via `DeadLetterPublishingRecoverer`.

**Rejected because:** DLT does not solve the problem it appears to solve here. Messages in the DLT are data lost until someone manually processes them. In a system with one operator (or a small team), that manual processing rarely happens; the DLT becomes a landfill. Worse, the primary consumer keeps advancing while events sit dead — the appearance of health while the audit log silently misses records.

For the failure mode being addressed (temporarily unavailable downstream dependencies), indefinite retry with visible lag is operationally simpler and semantically correct. If a message is genuinely poison (cannot succeed regardless of retry), it will stay stuck with growing lag — that will trigger investigation, and manual intervention can remove or fix the message. That is not worse than a DLT, and it does not require additional infrastructure.

If the volume of poison messages grows (which would indicate a bug elsewhere), revisit — but starting with indefinite retry and adding DLT reactively is safer than starting with DLT and discovering data loss retroactively.

### A2 — Lookup by resource ID at consume time (fat activity log despite thin events)

**Considered:** the consumer receives a thin event with a `taskId`, then queries task-service (or Postgres directly, given the consumer lives in `task-service` today) to load the current state of that task and store it in the activity log document.

**Rejected because:**

- **Consistency window**: the state at consume time is not the state at publish time. A task modified between publish and consume would produce misleading audit entries ("action=CREATED, title=X" where X is the current title, not the title at creation).
- **Coupling**: the consumer becomes dependent on the source-of-truth for reading, which contradicts the thin-events extraction argument (D10). Extracting to a dedicated `activity-service` would then require the extracted service to reach into task-service's database or call its API — reintroducing the coupling the extraction was meant to avoid.
- **Performance**: N events = N reads on the source. Fine at small scale; problematic under load.
- **Delete semantics**: what does "CATEGORY_DELETED" mean for a lookup? The row no longer exists. Special-casing per event type reintroduces complexity D10 was meant to avoid.

### A3 — Fat events at the publisher

**Considered:** the publisher includes all relevant business fields in the event payload (`title`, `status`, `oldStatus`, `newStatus`, `name`, etc.). The consumer stores them directly with no lookup.

**Rejected because:**

- Every field in the event becomes a permanent commitment. Renaming or removing fields breaks consumers.
- Consumers may inadvertently rely on internal fields that were included "just in case", making internal refactors of the publisher into breaking API changes.
- Schema evolution requires coordination even across a single team — with multiple teams (any real employer target), this becomes a permanent tax.
- Contradicts the industry direction toward event-driven architectures that decouple domain models from event contracts.

Thin events are the recognized best practice for exactly these reasons. The trade-off (loss of business detail in the audit log, per C3) is accepted.

### A4 — Event enrichment only for STATUS_CHANGED (option "2b")

**Considered mid-implementation**, session 14 block 5: enrich only `STATUS_CHANGED` events with `oldStatus` and `newStatus`, keeping all other event types thin. The reasoning was that status transitions have specific audit value ("when did this task go from IN_PROGRESS to DONE?") that would be lost with pure thin events.

**Rejected mid-block** after the developer reported cognitive fog ("me estoy haciendo un poco lío"). YAGNI honestly applied: no concrete requirement had emerged for this enrichment; the added asymmetry (one event type richer than others) would need its own justification when extraction happened; and the cost of adding it later (if a requirement emerges) is lower than the cost of maintaining an asymmetric contract permanently.

Kept alive as an option for future revisiting if concrete requirements emerge.

### A5 — `MongoTemplate` vs `Repository.insert()`

**Considered:** using the `ActivityEventRepository` (Spring Data MongoDB) with `insert()` semantics instead of `MongoTemplate.insert()` directly.

**Choice made:** `MongoTemplate` for explicitness. The `Repository.insert()` call would have worked identically — both raise `DuplicateKeyException` on `_id` conflict, both go through the same underlying driver.

**Rationale**: pedagogical explicitness during rollout, not technical necessity. Making the low-level path visible in the consumer makes the persistence step obvious to anyone reading the code, without needing to know Spring Data MongoDB's insert-vs-save semantics. Future refactor to `Repository.insert()` is cosmetic and low-risk.

**Correction (documented in session 14 tarde)**: an earlier version of this ADR draft argued `MongoTemplate` was "necessary" for idempotency. That was overstated. Repository would work equally. The choice is style, not capability.

### A6 — Transactional outbox pattern

**Considered:** publish events by writing them to an "outbox" table in the same Postgres transaction as the domain change, then have a separate process poll the outbox and publish to Kafka. Guarantees exactly-once semantics from the publisher's perspective (no lost events even if the broker is unreachable at publish time).

**Rejected for the initial implementation because:**

- Adds an outbox table, a polling worker or CDC connector (Debezium or similar), and coordination logic. Significant infrastructure for a scenario not yet observed in practice.
- The current `@TransactionalEventListener(AFTER_COMMIT)` (D9) covers the common case: events are not published if the transaction rolls back.
- The remaining gap (transaction commits + broker unreachable at that instant) is real but rare in a healthy system. When it occurs, the current implementation loses the event silently — this is an acknowledged deuda técnica, not a claim of exactly-once at the publisher.

**Documented as pending** in the Deuda técnica section. The right time to implement outbox is when either (a) the failure mode becomes observable in practice (metrics on failed `send()` calls), or (b) a compliance requirement mandates exactly-once publishing (banking, healthcare, regulated audit trails).

### A7 — Adding methods to the existing `ActivityEventListener`

**Considered briefly**, rejected quickly. The legacy `ActivityEventListener` was single-responsibility (activity log write). Adding Kafka publishing to the same class would have violated SRP for a saving of zero effort. Dedicated publishers (D8) are cleaner.

## Deuda técnica

Ordered by expected impact:

1. **Transactional outbox pattern** for guaranteed at-least-once publishing (closes the gap in D9 where transaction commit is followed by broker failure before publish). Implementation options: outbox table + polling worker; outbox table + Debezium CDC. Trigger for scheduling: metrics showing non-negligible rate of failed `send()` calls, or compliance requirement.

2. **Extraction of the consumer to a dedicated `activity-service`.** Blocked on: a second event producer joining the system, or Mongo Atlas M0 becoming insufficient for the audit collection. Not urgent; well-prepared for by D10 and C8.

3. **`serverSelectionTimeoutMS` of the Mongo driver.** Currently 30s (driver default). During downstream outages, each retry attempt consumes 30s waiting for the driver to give up. Reducing to 5-10s would make retries visibly faster in logs and dashboards without changing correctness. Cosmetic operational improvement; low priority.

4. **`spring.json.write.dates.as.timestamps: false`.** Currently timestamps in event payloads are serialized as fractional-second epoch numbers (`1784696105.850459207`). ISO-8601 strings would be more readable in `kafka-console-consumer.sh` output and in any downstream tooling that parses events without a Jackson `ObjectMapper` configured identically. One-line config change. Low priority, cosmetic.

5. **`logging.level.com.mtole.task.kafka: DEBUG`.** Add a profile-scoped or environment-variable-triggered DEBUG level for the Kafka packages, to enable `Received TaskEvent ...` and `Persisted ActivityEvent ...` logs during troubleshooting. Currently these lines never appear because default is INFO. Low priority.

6. **Kafka topic retention configuration.** Default retention is 7 days. For audit-adjacent topics, longer retention (30 days? 90 days?) may be worth setting explicitly so that consumer group resets can recover from bugs discovered later (see C4). Decision deferred until retention emerges as a limiting factor.

7. **Consumer lag monitoring.** Currently only observable manually via `kafka-consumer-groups.sh --describe`. Production readiness requires metrics export (Micrometer + Prometheus) and alerting on sustained lag growth. Not needed at current single-developer scale; required before any real user traffic.

8. **Semantics of `retries: N` in Spring Cloud Gateway 2025.0.0.** Inherited from ADR-006 (session 10). Not related to this ADR directly but tracked in the same technical debt bucket for the broader Kafka + gateway story.

## References

- ADR-001: Polyglot persistence (Postgres + MongoDB) for the activity log.
- ADR-004: Border authentication at the API gateway.
- ADR-005: Profile-specific configuration pattern (`application.yml` base + `application-docker.yml` overrides).
- ADR-006: Resilience patterns (Circuit Breaker + Retry + TimeLimiter) at the API gateway.
- Commit `28da0d8`: fix of the retry policy (context in Consequence C1).
- Commit `8bb1206`: removal of the legacy `ActivityEventListener` (context in D7).
- Commit `2d859b5`: initial Kafka consumer for the activity log.
- Commits `2e417d6`, `cb7b69f`: Kafka publishers for Task and Category events.
- Commit `b34a24a`: Spring Kafka client configuration in task-service.
- Commit `2e1802b`: Kafka broker in Docker Compose.
