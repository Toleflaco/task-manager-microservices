package com.mtole.task.tasks;

import com.mtole.task.common.ResourceNotFoundException;
import com.mtole.task.common.dto.PagedResponse;
import com.mtole.task.security.SecurityUtils;
import com.mtole.task.tasks.dto.*;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/tasks")
@Tag(name = "Tasks", description = "CRUD task")
public class TaskController {

    private final TaskService taskService;
    private final TaskMapper taskMapper;

    public TaskController(TaskService taskService, TaskMapper taskMapper) {

        this.taskService = taskService;
        this.taskMapper = taskMapper;
    }

    @Operation(summary = "Create a new task", description = "Create a new task with an unique title")
    @ApiResponses({
            @ApiResponse(responseCode = "201", description = "Task created"),
            @ApiResponse(responseCode = "400", description = "Invalid input data", content = @Content)
    })
    @PostMapping
    public ResponseEntity<TaskResponse> addTask(@Valid @RequestBody TaskCreateRequest request) {
        Long currentUserId = SecurityUtils.currentUserId();
        Task created = taskService.create(request, currentUserId);
        return ResponseEntity.status(HttpStatus.CREATED).body(taskMapper.toResponse(created));
    }

    @Operation(summary = "Update a task", description = "Update task with id")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "Task updated"),
            @ApiResponse(responseCode = "400", description = "Invalid input data")
    })
    @PutMapping("/{id}")
    public TaskResponse updateTaskById(@PathVariable Long id,
                                       @Valid @RequestBody TaskUpdateRequest request) {
        Long currentUserId = SecurityUtils.currentUserId();
        return taskMapper.toResponse(taskService.update(id, request, currentUserId));
    }

    @Operation(summary = "Delete task", description = "Delete task with id")
    @ApiResponses({
            @ApiResponse(responseCode = "204", description = "Task deleted"),
            @ApiResponse(responseCode = "404", description = "Task not found")
    })
    @DeleteMapping("/{id}")
    public ResponseEntity<Void> deleteTaskById(@PathVariable Long id) {
        Long currentUserId = SecurityUtils.currentUserId();
        if (!taskService.deleteById(id, currentUserId)) {
            throw new ResourceNotFoundException("Task with id=" + id + " not found");
        }
        return ResponseEntity.noContent().build();
    }

    @Operation(summary = "Complete task", description = "Complete the task with id")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "Task successfully marked as completed"),
            @ApiResponse(responseCode = "404", description = "Task not found or not owned by current user"),
            @ApiResponse(responseCode = "409", description = "Invalid state transition")
    })
    @PostMapping("/{id}/complete")
    public TaskResponse completeTaskById(@PathVariable Long id) {
        Long currentUserId = SecurityUtils.currentUserId();
        Task updated = taskService.complete(id, currentUserId);
        return taskMapper.toResponse(updated);
    }

    @Operation(summary = "Cancel task", description = "Cancel the task with id")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "Task successfully marked as cancelled"),
            @ApiResponse(responseCode = "404", description = "Task not found or not owned by current user"),
            @ApiResponse(responseCode = "409", description = "Invalid state transition")
    })
    @PostMapping("/{id}/cancel")
    public TaskResponse cancelTaskById(@PathVariable Long id) {
        Long currentUserId = SecurityUtils.currentUserId();
        Task updated = taskService.cancel(id, currentUserId);
        return taskMapper.toResponse(updated);
    }

    @Operation(summary = "Search for a task by ID", description = "Searches for a task by ID; if it is not found, it returns an exception")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "Task found"),
            @ApiResponse(responseCode = "404", description = "Task not found", content = @Content)
    })
    @GetMapping("/{id}")
    public TaskResponse findTaskById(@PathVariable Long id) {
        Long currentUserId = SecurityUtils.currentUserId();
        return taskService.findById(id, currentUserId)
                .map(taskMapper::toResponse)
                .orElseThrow(() -> new ResourceNotFoundException("Task with id=" + id + " not found"));
    }

    @Operation(summary = "List all tasks for currentUserId", description = "List all tasks by page")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "Paginated list of tasks for the current user"),
            @ApiResponse(responseCode = "400", description = "Invalid query parameter", content = @Content)
    })
    @GetMapping
    public PagedResponse<TaskSummaryProjection> findAll(
            @Parameter(description = "Optional filter by task status", example = "PENDING")
            @RequestParam(required = false) TaskStatus status,
            @Parameter(description = "Optional filter by task priority", example = "HIGH")
            @RequestParam(required = false) Priority priority,
            @Parameter(description = "Optional filter by exact category name", example = "Trabajo")
            @RequestParam(required = false) String categoryName,

            Pageable pageable) {
        Long currentUserId = SecurityUtils.currentUserId();
        TaskFilter filter = new TaskFilter(status, priority, categoryName);
        Page<TaskSummaryProjection> page = taskService.findAll(currentUserId, filter, pageable);

        return new PagedResponse<>(
                page.getContent(),
                page.getNumber(),
                page.getSize(),
                page.getTotalElements()
        );
    }

    @Operation(summary = "Stats of tasks for currentUserId", description = "stats of tasks for currentUserId")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "Stats of tasks for the current user"),
     
    })
    @GetMapping("/stats")
    public TaskStatsResponse getStats() {
        Long currentUserId = SecurityUtils.currentUserId();
        return taskService.getStats(currentUserId);
    }

}

