# Flujo del `POST /tasks`

## 🧭 Diagrama (Mermaid)

```mermaid
sequenceDiagram
    autonumber
    participant C as Cliente
    participant G as API Gateway
    participant JF as JwtValidationFilter
    participant TS as Task-service (HTTP)
    participant HAF as HeaderAuthenticationFilter
    participant SC as SecurityContextHolder
    participant TC as TaskController
    participant TServ as TaskService (@Transactional)
    participant PG as Postgres
    participant KP as TaskEventKafkaPublisher (AFTER_COMMIT)
    participant K as Kafka (task.events)
    participant AC as ActivityLogKafkaConsumer
    participant MG as MongoDB

    C->>G: POST /tasks (Authorization: Bearer JWT)
    G->>JF: validar JWT offline
    JF->>JF: extraer sub (userId)
    JF->>G: añadir X-User-Id
    G->>TS: forward POST /tasks (X-User-Id)

    TS->>HAF: HeaderAuthenticationFilter
    HAF->>SC: set Authentication(userId)
    TS->>TC: TaskController.create()
    TC->>TServ: create(userId, payload)

    TServ->>PG: INSERT task
    TServ->>TServ: publicar TaskCreatedEvent in-process

    Note over TServ,PG: fin de método → Spring hace COMMIT

    %% AFTER_COMMIT ocurre aquí, antes de devolver al controller
    TServ->>KP: ejecutar listener AFTER_COMMIT
    KP->>KP: construir thin event
    KP->>K: send("task.events", userId, event)
    K-->>KP: ack

    TServ-->>TC: Task creado
    TC-->>C: 201 Created + JSON

    %% Consumo asíncrono
    K->>AC: consumir evento
    AC->>MG: INSERT activity log
    AC-->>K: ack
🧩 Notas del flujo
🔹 Punto de entrada y autenticación
El cliente llama a POST /tasks con JWT.

El gateway valida el JWT offline, extrae sub, añade X-User-Id.

Forward al task-service.

🔹 HeaderAuthenticationFilter
Si X-User-Id existe y es numérico → crea Authentication y lo mete en SecurityContextHolder.

Si no existe o no es numérico → no autentica nada.
La cadena de filtros de Spring Security devuelve 401.
Esto protege el servicio si alguien intenta saltarse el gateway.

🔹 Lógica de dominio (TaskService.create())
@Transactional.

Valida categoría si viene.

Inserta la tarea en Postgres.

Publica un evento de dominio in-process (TaskCreatedEvent).

🔹 Commit y AFTER_COMMIT (orden real)
El orden correcto:

TaskService.create() termina.

Spring ejecuta el commit de la transacción.

Inmediatamente después del commit, antes de devolver al controller:

se ejecuta el listener TaskEventKafkaPublisher (AFTER_COMMIT),

se construye el thin event,

se hace kafkaTemplate.send().

Solo después de todo esto el método vuelve al controller.

🔹 Síncrono vs asíncrono (precisión)
✔ Publicación a Kafka → síncrona respecto al HTTP
El hilo HTTP:

ejecuta el commit,

ejecuta el listener,

ejecuta el send(),

espera el ack del broker,

solo entonces devuelve 201 Created al cliente.

Si Kafka va lento, el cliente espera.

✔ Consumo en Mongo → asíncrono respecto al productor
El consumer corre en su propio hilo.

Procesa el evento cuando Kafka lo entrega.

Escribe en Mongo sin bloquear la respuesta al cliente.

🔹 Escritura en Mongo
ActivityLogKafkaConsumer consume el evento.

Inserta un documento de actividad en Mongo.

Confirma el ack a Kafka.

🕳️ Huecos sembrados para fases futuras
🔸 Dual-write residual
Si hay crash entre commit y send(), Postgres tiene la fila pero Kafka no tiene el evento.
Se resolverá en Fase 5 con outbox.

🔸 Particiones y orden
Kafka garantiza orden por clave (userId).
El detalle fino se aborda en Fase 1.

🔸 Thin vs fat events
Hoy: thin events.
La decisión formal se toma en Fase 2.
