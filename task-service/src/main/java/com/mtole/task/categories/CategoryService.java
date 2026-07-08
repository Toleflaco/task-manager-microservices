package com.mtole.task.categories;

import com.mtole.task.categories.dto.CategoryCreateRequest;
import com.mtole.task.categories.events.CategoryCreatedEvent;
import com.mtole.task.categories.events.CategoryDeletedEvent;
import com.mtole.task.categories.events.CategoryUpdatedEvent;
import com.mtole.task.common.ResourceNotFoundException;
import com.mtole.task.tasks.TaskRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.Optional;

@Service
public class CategoryService {
    private final CategoryRepository categoryRepository;
    private final CategoryMapper categoryMapper;
    private final TaskRepository taskRepository;
    private final ApplicationEventPublisher eventPublisher;
    private static final Logger log = LoggerFactory.getLogger(CategoryService.class);

    public CategoryService(
            CategoryRepository categoryRepository,
            CategoryMapper categoryMapper,
            TaskRepository taskRepository,
            ApplicationEventPublisher eventPublisher) {
        this.categoryRepository = categoryRepository;
        this.categoryMapper = categoryMapper;
        this.taskRepository = taskRepository;
        this.eventPublisher = eventPublisher;
    }

    @Transactional
    public Category create(CategoryCreateRequest request, Long currentUserId) {
        log.info("Creating category with name={}", request.name());
        Category entity = categoryMapper.toEntity(request);
        entity.setUserId(currentUserId);
        Category saved = categoryRepository.save(entity);
        log.info("Created category with id={}", saved.getId());

        eventPublisher.publishEvent(new CategoryCreatedEvent(
                saved.getId(),
                currentUserId,
                saved.getName(),
                Instant.now()
        ));

        return saved;
    }

    @Transactional
    public Category update(Long id, CategoryCreateRequest request, Long currentUserId) {
        log.info("Updating category with id={}", id);
        Category existing = categoryRepository.findByIdAndUserId(id, currentUserId)
                .orElseThrow(() -> new ResourceNotFoundException("Category with id=" + id + " not found"));

        categoryMapper.updateFromRequest(request, existing);
        Category saved = categoryRepository.save(existing);
        log.info("Updated category with id={}", saved.getId());

        eventPublisher.publishEvent(new CategoryUpdatedEvent(
                saved.getId(),
                currentUserId,
                Instant.now()
        ));

        return saved;
    }

    @Transactional(readOnly = true)
    public Optional<Category> findById(Long id, Long currentUserId) {
        return categoryRepository.findByIdAndUserId(id, currentUserId);
    }

    @Transactional(readOnly = true)
    public Page<Category> findAll(Long currentUserId, Pageable pageable) {
        return categoryRepository.findAllByUserId(currentUserId, pageable);
    }

    @Transactional(readOnly = true)
    public long countAll(Long currentUserId) {
        return categoryRepository.countByUserId(currentUserId);
    }

    @Transactional
    public boolean deleteById(Long id, Long currentUserId) {
        log.info("Deleting category with id={}", id);

        Optional<Category> existing = categoryRepository.findByIdAndUserId(id, currentUserId);
        if (existing.isEmpty()) {
            log.warn("Category with id={} not found or not owned by user={}", id, currentUserId);
            return false;
        }
        Category category = existing.get();
        String name = category.getName();

        taskRepository.disassociateFromCategory(id);
        categoryRepository.delete(category);
        log.info("Deleted category id={}", id);

        eventPublisher.publishEvent(new CategoryDeletedEvent(
                id,
                currentUserId,
                name,
                Instant.now()
        ));

        return true;
    }
}
