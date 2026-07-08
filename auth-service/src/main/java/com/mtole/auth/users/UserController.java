package com.mtole.auth.users;

import com.mtole.auth.common.ResourceNotFoundException;
import com.mtole.auth.security.SecurityUtils;
import com.mtole.auth.users.dto.UserCreateRequest;
import com.mtole.auth.users.dto.UserResponse;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

@Tag(name = "Users", description = "CRUD users")
@RestController
@RequestMapping("/users")
public class UserController {
    private final UserService userService;
    private final UserMapper userMapper;

    public UserController(UserService userService, UserMapper userMapper) {
        this.userService = userService;
        this.userMapper = userMapper;
    }

    @Operation(summary = "Create a new user", description = "Create a new user with unique name and email ")
    @ApiResponses({
            @ApiResponse(responseCode = "201", description = "user created"),
            @ApiResponse(responseCode = "400", description = "Invalid input Data", content = @Content)
    })
    @PostMapping
    public ResponseEntity<UserResponse> createUser(@Valid @RequestBody UserCreateRequest request) {
        User createdUser = userService.create(request);
        return ResponseEntity.status(HttpStatus.CREATED).body(userMapper.toResponse(createdUser));
    }

    @Operation(summary = "Search for a user by ID", description = "Searches for a user by ID; if it is not found, it returns an exception")
    @GetMapping("/{id}")
    public UserResponse findUserById(@PathVariable Long id) {
        Long currentUserId = SecurityUtils.currentUserId();
        if (!id.equals(currentUserId)) {
            throw new ResourceNotFoundException("User not found with id " + id);
        }
        return userService.findById(id)
                .map(userMapper::toResponse)
                .orElseThrow(() -> new ResourceNotFoundException("User not found with id " + id));
    }

    @Operation(summary = "Delete user", description = "Delete user with specific Id")
    @ApiResponses({
            @ApiResponse(responseCode = "204", description = "Delete user"),
            @ApiResponse(responseCode = "404", description = "The user could not be deleted", content = @Content)
    })
    @DeleteMapping("/{id}")
    public ResponseEntity<Void> deleteUserById(@PathVariable Long id) {
        Long currentUserId = SecurityUtils.currentUserId();
        if (!id.equals(currentUserId)) {
            throw new ResourceNotFoundException("User not found with id " + id);
        }
        if (!userService.deleteById(id)) {
            throw new ResourceNotFoundException("User not found with id " + id);
        }
        return ResponseEntity.noContent().build();

    }
}
