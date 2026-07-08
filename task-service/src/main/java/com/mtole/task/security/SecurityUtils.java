package com.mtole.task.security;

import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;

public final class SecurityUtils {
    private SecurityUtils() {

    }

    public static Long currentUserId() {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();

        if (auth == null || !auth.isAuthenticated()) {
            throw new IllegalStateException("No authenticated user in security context");
        }
        Object principal = auth.getPrincipal();
        if (!(principal instanceof Long userId)) {
            throw new IllegalStateException("Unexpected principal type: " + principal.getClass().getName());
        }
        return userId;
    }
}
