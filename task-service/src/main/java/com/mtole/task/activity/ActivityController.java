package com.mtole.task.activity;

import com.mtole.task.activity.dto.ActivityEventResponse;
import com.mtole.task.activity.dto.ActivityStatsResponse;
import com.mtole.task.common.dto.PagedResponse;
import com.mtole.task.security.SecurityUtils;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.data.web.PageableDefault;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.Instant;

@RestController
@RequestMapping("/me/activity")
@Tag(name = "Activity", description = "Audit log of user actions")
public class ActivityController {

    private final ActivityEventRepository repository;
    private final ActivityEventMapper mapper;

    public ActivityController(ActivityEventRepository repository,
                              ActivityEventMapper mapper) {
        this.repository = repository;
        this.mapper = mapper;
    }

    @Operation(summary = "List activity events for current user",
            description = "Paginated audit log with optional filters")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "Activity events listed"),
            @ApiResponse(responseCode = "401", description = "Not authenticated")
    })
    @GetMapping
    public PagedResponse<ActivityEventResponse> listActivity(
            @Parameter(description = "Filter by start timestamp (inclusive)")
            @RequestParam(required = false)
            @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) Instant from,

            @Parameter(description = "Filter by end timestamp (inclusive)")
            @RequestParam(required = false)
            @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) Instant to,

            @Parameter(description = "Filter by resource type", example = "TASK")
            @RequestParam(required = false) String resourceType,

            @Parameter(description = "Filter by resource id")
            @RequestParam(required = false) Long resourceId,

            @PageableDefault(size = 20, sort = "timestamp", direction = Sort.Direction.DESC)
            Pageable pageable
    ) {
        Long currentUserId = SecurityUtils.currentUserId();
        ActivityEventFilter filter = new ActivityEventFilter(from, to, resourceType, resourceId);

        Page<ActivityEvent> page = repository.search(currentUserId, filter, pageable);

        return new PagedResponse<>(
                page.getContent().stream().map(mapper::toResponse).toList(),
                page.getNumber(),
                page.getSize(),
                page.getTotalElements()
        );
    }

    @Operation(summary = "Activity statistics for current user",
            description = "Aggregated statistics for the audit log, with optional date range filtering")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "Statistics computed"),
            @ApiResponse(responseCode = "401", description = "Not authenticated")
    })
    @GetMapping("/stats")
    public ActivityStatsResponse getStats(
            @Parameter(description = "Filter by start timestamp (inclusive)")
            @RequestParam(required = false)
            @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) Instant from,

            @Parameter(description = "Filter by end timestamp (inclusive)")
            @RequestParam(required = false)
            @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) Instant to
    ) {
        Long currentUserId = SecurityUtils.currentUserId();
        ActivityStatsFilter filter = new ActivityStatsFilter(from, to);
        return repository.getStats(currentUserId, filter);
    }
}

