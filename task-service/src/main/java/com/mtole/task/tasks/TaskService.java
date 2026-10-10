package com.mtole.task.tasks;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.mtole.task.categories.Category;
import com.mtole.task.categories.CategoryRepository;
import com.mtole.task.common.ResourceNotFoundException;
import com.mtole.task.kafka.events.TaskEvent;
import com.mtole.task.kafka.events.TaskEventType;
import com.mtole.task.outbox.OutboxEvent;
import com.mtole.task.outbox.OutboxRepository;
import com.mtole.task.tasks.dto.TaskCreateRequest;
import com.mtole.task.tasks.dto.TaskStatsResponse;
import com.mtole.task.tasks.dto.TaskSummaryProjection;
import com.mtole.task.tasks.dto.TaskUpdateRequest;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.OptimisticLockingFailureException;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.Optional;
import java.util.UUID;

@Service
public class TaskService {
    private final TaskRepository taskRepository;
    private final CategoryRepository categoryRepository;
    private final TaskMapper taskMapper;
    private final OutboxRepository outboxRepository;
    private final ObjectMapper objectMapper;
    private static final Logger log = LoggerFactory.getLogger(TaskService.class);

    public TaskService(
            TaskRepository taskRepository,
            CategoryRepository categoryRepository,
            TaskMapper taskMapper,
            OutboxRepository outboxRepository,
            ObjectMapper objectMapper) {
        this.taskRepository = taskRepository;
        this.categoryRepository = categoryRepository;
        this.taskMapper = taskMapper;
        this.outboxRepository = outboxRepository;
        this.objectMapper = objectMapper;
    }

    @Transactional
    public Task create(TaskCreateRequest request, Long currentUserId) {
        log.info("Creating task with title={}", request.title());
        Category category = null;
        if (request.categoryId() != null) {
            category = categoryRepository.findByIdAndUserId(request.categoryId(), currentUserId)
                    .orElseThrow(() -> new ResourceNotFoundException("Category not found"));
        }
        Task entity = taskMapper.toEntity(request);
        entity.setCategory(category);
        entity.setUserId(currentUserId);
        entity.setStatus(TaskStatus.PENDING);

        Task saved = taskRepository.save(entity);
        log.info("Task created with id={}", saved.getId());

        appendOutboxEvent(currentUserId, saved.getId(), TaskEventType.CREATED, Instant.now());

        return saved;
    }

    @Transactional
    public Task update(Long id, TaskUpdateRequest request, Long currentUserId) {
        log.info("Updating task with id={}", id);
        Task existing = taskRepository.findByIdAndUserId(id, currentUserId)
                .orElseThrow(() -> new ResourceNotFoundException("Task with id=" + id + " not found"));

        if (request.version() != null && !request.version().equals(existing.getVersion())) {
            throw new OptimisticLockingFailureException(
                    "Task " + id + " was modified by another request"
            );
        }
        Category category = null;
        if (request.categoryId() != null) {
            category = categoryRepository.findByIdAndUserId(request.categoryId(), currentUserId)
                    .orElseThrow(() -> new ResourceNotFoundException("Category with id=" + request.categoryId() + " not found"));
        }

        taskMapper.updateFromRequest(request, existing);
        existing.setCategory(category);
        Task saved = taskRepository.save(existing);
        log.info("Task updated with id={}", saved.getId());

        appendOutboxEvent(currentUserId, saved.getId(), TaskEventType.UPDATED, Instant.now());

        return saved;
    }

    @Transactional
    public Task complete(Long id, Long currentUserId) {
        log.info("Completing task with id={}", id);
        Task existing = taskRepository.findByIdAndUserId(id, currentUserId)
                .orElseThrow(() -> new ResourceNotFoundException("Task with id=" + id + " not found"));
        TaskStatus currentStatus = existing.getStatus();
        if (currentStatus != TaskStatus.PENDING && currentStatus != TaskStatus.IN_PROGRESS) {
            throw new InvalidTaskStateException("Cannot complete task with id=" + id + ", current status is " + currentStatus);
        }
        existing.setStatus(TaskStatus.COMPLETED);
        existing.setCompletedAt(OffsetDateTime.now(ZoneOffset.UTC));
        Task saved = taskRepository.save(existing);
        log.info("Task completed with id={}", saved.getId());

        appendOutboxEvent(currentUserId, saved.getId(), TaskEventType.STATUS_CHANGED, Instant.now());

        return saved;
    }

    @Transactional
    public Task cancel(Long id, Long currentUserId) {
        log.info("Canceling task with id={}", id);
        Task existing = taskRepository.findByIdAndUserId(id, currentUserId)
                .orElseThrow(() -> new ResourceNotFoundException("Task with id=" + id + " not found"));
        TaskStatus currentStatus = existing.getStatus();
        if (currentStatus != TaskStatus.PENDING && currentStatus != TaskStatus.IN_PROGRESS) {
            throw new InvalidTaskStateException(
                    "Cannot cancel task with id=" + id + ", current status is " + currentStatus);
        }
        existing.setStatus(TaskStatus.CANCELLED);
        Task saved = taskRepository.save(existing);
        log.info("Task cancelled with id={}", saved.getId());

        appendOutboxEvent(currentUserId, saved.getId(), TaskEventType.STATUS_CHANGED, Instant.now());

        return saved;
    }

    @Transactional(readOnly = true)
    public Page<TaskSummaryProjection> findAll(Long currentUserId, TaskFilter filter, Pageable pageable) {
        Specification<Task> spec = buildSpecification(currentUserId, filter);
        return taskRepository.findAllSummariesBy(spec, pageable);
    }

    private Specification<Task> buildSpecification(Long currentUserId, TaskFilter filter) {
        Specification<Task> spec = TaskSpecifications.byUserId(currentUserId);

        if (filter.status() != null) {
            spec = spec.and(TaskSpecifications.byStatus(filter.status()));
        }
        if (filter.priority() != null) {
            spec = spec.and(TaskSpecifications.byPriority(filter.priority()));
        }
        if (filter.categoryName() != null && !filter.categoryName().isBlank()) {
            spec = spec.and(TaskSpecifications.byCategoryName(filter.categoryName()));
        }
        return spec;
    }

    @Transactional(readOnly = true)
    public Optional<Task> findById(Long id, Long currentUserId) {
        return taskRepository.findByIdAndUserId(id, currentUserId);
    }

    @Transactional
    public boolean deleteById(Long id, Long currentUserId) {
        log.info("Deleting task with id={}", id);
        Optional<Task> existing = taskRepository.findByIdAndUserId(id, currentUserId);
        if (existing.isEmpty()) {
            log.warn("Task with id={} not found or not owned by user={}", id, currentUserId);
            return false;
        }
        Task task = existing.get();

        taskRepository.delete(task);
        log.info("Deleted task id={}", id);

        appendOutboxEvent(currentUserId, id, TaskEventType.DELETED, Instant.now());

        return true;
    }

    @Transactional(readOnly = true)
    public TaskStatsResponse getStats(Long currentUserId) {
        return taskRepository.findStatsByUserId(currentUserId);
    }

    /**
     * Apunta un evento en la tabla outbox_events dentro de la transacción actual.
     * El poller @Scheduled (K01-L) lo recogerá y lo publicará a Kafka.
     * Ver docs/adr-008-outbox-pattern-polling.md.
     */
    private void appendOutboxEvent(Long userId, Long taskId, TaskEventType type, Instant occurredAt) {
        TaskEvent kafkaEvent = new TaskEvent(
                UUID.randomUUID(),
                type,
                userId,
                taskId,
                occurredAt
        );
        String payload;
        try {
            payload = objectMapper.writeValueAsString(kafkaEvent);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("Failed to serialize TaskEvent for outbox", e);
        }
        OutboxEvent outboxEvent = new OutboxEvent(
                "task",
                taskId.toString(),
                "task." + type.name().toLowerCase(),
                payload
        );
        outboxRepository.save(outboxEvent);
    }
}
