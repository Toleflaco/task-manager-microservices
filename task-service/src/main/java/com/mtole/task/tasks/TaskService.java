package com.mtole.task.tasks;

import com.mtole.task.categories.Category;
import com.mtole.task.categories.CategoryRepository;
import com.mtole.task.common.ResourceNotFoundException;
import com.mtole.task.tasks.dto.TaskCreateRequest;
import com.mtole.task.tasks.dto.TaskStatsResponse;
import com.mtole.task.tasks.dto.TaskSummaryProjection;
import com.mtole.task.tasks.dto.TaskUpdateRequest;
import com.mtole.task.tasks.events.TaskCreatedEvent;
import com.mtole.task.tasks.events.TaskDeletedEvent;
import com.mtole.task.tasks.events.TaskStatusChangedEvent;
import com.mtole.task.tasks.events.TaskUpdatedEvent;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.ApplicationEventPublisher;
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

@Service
public class TaskService {
    private final TaskRepository taskRepository;
    private final CategoryRepository categoryRepository;
    private final TaskMapper taskMapper;
    private final ApplicationEventPublisher eventPublisher;
    private static final Logger log = LoggerFactory.getLogger(TaskService.class);

    public TaskService(
            TaskRepository taskRepository,
            CategoryRepository categoryRepository,
            TaskMapper taskMapper,
            ApplicationEventPublisher eventPublisher) {
        this.taskRepository = taskRepository;
        this.categoryRepository = categoryRepository;
        this.taskMapper = taskMapper;
        this.eventPublisher = eventPublisher;
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

        eventPublisher.publishEvent(new TaskCreatedEvent(
                saved.getId(),
                currentUserId,
                saved.getTitle(),
                saved.getStatus().name(),
                saved.getCategory() != null ? saved.getCategory().getId() : null,
                Instant.now()
        ));

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

        eventPublisher.publishEvent(new TaskUpdatedEvent(
                saved.getId(),
                currentUserId,
                Instant.now()
        ));

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

        eventPublisher.publishEvent(new TaskStatusChangedEvent(
                saved.getId(),
                currentUserId,
                currentStatus.name(),
                TaskStatus.COMPLETED.name(),
                Instant.now()
        ));

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

        eventPublisher.publishEvent(new TaskStatusChangedEvent(
                saved.getId(),
                currentUserId,
                currentStatus.name(),
                TaskStatus.CANCELLED.name(),
                Instant.now()
        ));

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
        String title = task.getTitle();
        String status = task.getStatus().name();

        taskRepository.delete(task);
        log.info("Deleted task id={}", id);

        eventPublisher.publishEvent(new TaskDeletedEvent(
                id,
                currentUserId,
                title,
                status,
                Instant.now()
        ));

        return true;
    }

    @Transactional(readOnly = true)
    public TaskStatsResponse getStats(Long currentUserId) {
        return taskRepository.findStatsByUserId(currentUserId);
    }
}
