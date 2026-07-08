package com.mtole.task.activity;

import com.mtole.task.activity.dto.ActivityStatsResponse;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;

public interface ActivityEventRepositoryCustom {

    Page<ActivityEvent> search(Long userId, ActivityEventFilter filter, Pageable pageable);
    ActivityStatsResponse getStats(Long userId, ActivityStatsFilter filter);
}
