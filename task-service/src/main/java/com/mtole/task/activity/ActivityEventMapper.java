package com.mtole.task.activity;

import com.mtole.task.activity.dto.ActivityEventResponse;
import org.mapstruct.Mapper;
import org.mapstruct.ReportingPolicy;

@Mapper(componentModel = "spring", unmappedTargetPolicy = ReportingPolicy.ERROR)
public interface ActivityEventMapper {

    ActivityEventResponse toResponse(ActivityEvent entity);
}
