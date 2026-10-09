# ADR-008: Outbox pattern (polling manual) para publicación de eventos a Kafka

- **Status**: Proposed
- **Date**: 2026-10-09
- **Scope**: `task-service` — publicación de eventos de dominio a Kafka

## Context

El servicio `task-service` publica eventos de dominio en Kafka tras operaciones transaccionales sobre PostgreSQL. La implementación actual usa `@TransactionalEventListener(AFTER_COMMIT)` junto con `KafkaTemplate.send(...)`.

Este enfoque presenta dos problemas:

### 1. Ventana "fila sin evento"

La transacción de Postgres puede confirmar correctamente el cambio de estado del agregado, pero el listener puede fallar antes o durante la publicación del evento, dejando el sistema en un estado inconsistente:

- el agregado ha cambiado,
- pero el evento no se ha publicado.

### 2. `KafkaTemplate.send` es asíncrono y no participa en la transacción de Postgres

La publicación en Kafka no forma parte de la atomicidad del commit. Si el envío falla, no existe rollback del cambio de negocio.

El sistema necesita garantía **at-least-once end-to-end** entre Postgres y Kafka, evitando ventanas de inconsistencia y sin introducir infraestructura adicional en esta fase.

## Decision

Adoptar **Outbox Pattern con polling manual**, implementado mediante:

- Tabla `outbox_events` en el mismo Postgres del `task-service`.
- Poller `@Scheduled` que procesa eventos pendientes en orden FIFO con concurrencia segura.
- Publicación a Kafka mediante `KafkaTemplate.send` con `key = userId` para mantener orden causal.
- Retención mediante un segundo `@Scheduled` que limpia eventos antiguos.

### Desactivación explícita del publicador actual

El `TaskEventKafkaPublisher` existente (`@TransactionalEventListener(AFTER_COMMIT)`) **se desactiva** como parte de este cambio. Convive mal con outbox y causaría doble publicación. El poller asume en exclusiva la responsabilidad de propagar los eventos a Kafka.

### Diseños cerrados en esta decisión

#### Tabla `outbox_events`

| Columna | Tipo | Notas |
|---|---|---|
| `id` | `UUID` | PK |
| `aggregate_type` | `VARCHAR(50)` | Ej. `task`, `category` |
| `aggregate_id` | `VARCHAR(100)` | Id del agregado como string |
| `event_type` | `VARCHAR(100)` | Ej. `task.created`, `task.completed` |
| `payload` | `JSONB` | Thin event serializado |
| `created_at` | `TIMESTAMPTZ` | `NOT NULL DEFAULT NOW()` |
| `published_at` | `TIMESTAMPTZ` | `NULL` = pendiente |
| `attempts` | `INT` | `NOT NULL DEFAULT 0` |
| `last_error` | `TEXT` | `NULL` por defecto |

Índice parcial sobre eventos pendientes para que el escaneo del poller se mantenga barato aunque la tabla crezca:

```sql
CREATE INDEX ix_outbox_pending
  ON outbox_events (created_at)
  WHERE published_at IS NULL;
```

#### Orden del poller

- FIFO absoluto: `ORDER BY created_at ASC`.
- Concurrencia segura para múltiples instancias del `task-service`: `FOR UPDATE SKIP LOCKED`.
- El orden causal por usuario lo garantiza la `key = userId` del `send` (misma key → misma partición Kafka → orden preservado), no el poller.

#### Gestión de fallos en la publicación

- Si `send` falla → **no marcar `published_at`**.
- Incrementar `attempts`.
- Guardar `last_error` para diagnóstico.
- El poller reintenta en el siguiente tick. Backoff real se pospone a Fase 3.

#### Retención

- Segundo `@Scheduled` que elimina eventos con `published_at < NOW() - interval '7 days'`.
- Simple, mantiene la tabla pequeña, deja margen de debugging reciente.

## Alternatives Considered

### Seguir con `@TransactionalEventListener(AFTER_COMMIT)`

**Rechazado**:

- No elimina la ventana "fila sin evento".
- No garantiza atomicidad entre cambio de negocio y publicación.

### CDC con Debezium

**Rechazado para esta fase**:

- Añade infraestructura adicional (Kafka Connect + plugin Debezium).
- Acopla el contrato del evento al modelo físico de la BD.
- Se mantiene como variante alternativa evaluable dentro de la propia Fase 2 si el polling manual muestra limitaciones en producción.

## Consequences

### Positivas

- Garantía **at-least-once end-to-end** entre Postgres y Kafka.
- La decisión de publicar queda dentro de la misma transacción atómica del cambio de negocio.
- El sistema evita la ventana inconsistente del enfoque actual.
- Poller seguro para múltiples instancias gracias a `SKIP LOCKED`.
- Eliminación del riesgo de doble publicación al desactivar el listener actual.

### Negativas / Trade-offs

- Latencia introducida por el intervalo del poller (configurable; valor inicial ~500 ms).
- Carga adicional sobre Postgres por la consulta recurrente.
- Los consumers deben ser **idempotentes** (dedupe en consumer previsto para Fase 3).
- Complejidad operativa ligeramente mayor respecto al listener directo.
