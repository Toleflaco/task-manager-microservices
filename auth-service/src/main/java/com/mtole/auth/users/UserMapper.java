package com.mtole.auth.users;

import com.mtole.auth.users.dto.UserCreateRequest;
import com.mtole.auth.users.dto.UserResponse;
import org.mapstruct.Mapper;
import org.mapstruct.Mapping;
import org.mapstruct.ReportingPolicy;

@Mapper(componentModel = "spring",
        unmappedTargetPolicy = ReportingPolicy.ERROR)
public interface UserMapper {
    @Mapping(target = "id", ignore = true)
    @Mapping(target = "createdAt", ignore = true)
    @Mapping(target = "updatedAt", ignore = true)
    @Mapping(target = "password", ignore = true)
    @Mapping(target = "version", ignore = true)
    User toEntity(UserCreateRequest request);

    UserResponse toResponse(User user);
}
