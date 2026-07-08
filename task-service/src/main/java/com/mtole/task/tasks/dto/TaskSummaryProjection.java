package com.mtole.task.tasks.dto;

import com.mtole.task.tasks.Priority;
import com.mtole.task.tasks.TaskStatus;

import java.time.OffsetDateTime;

public interface TaskSummaryProjection {
    Long getId();

    String getTitle();

    TaskStatus getStatus();

    Priority getPriority();

    OffsetDateTime getDueDate();

    OffsetDateTime getCreatedAt();

    Long getCategoryId();

    String getCategoryName();

}
