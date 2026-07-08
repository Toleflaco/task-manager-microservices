package com.mtole.task.tasks;

import com.mtole.task.categories.Category;
import jakarta.persistence.criteria.Join;
import org.springframework.data.jpa.domain.Specification;

public final class TaskSpecifications {

    private TaskSpecifications() {
        // utility class, prevent instantiation
    }

    public static Specification<Task> byUserId(Long userId) {
        return (root, query, cb) -> cb.equal(root.get("user").get("id"), userId);
    }

    public static Specification<Task> byStatus(TaskStatus status) {
        return (root, query, cb) -> cb.equal(root.get("status"), status);
    }
    public static Specification<Task> byPriority(Priority priority) {
        return (root, query, cb) -> cb.equal(root.get("priority"), priority);
    }
    public static Specification<Task> byCategoryName(String categoryName) {
        return (root, query, cb) -> {
            Join<Task, Category> categoryJoin = root.join("category");
            return cb.equal(categoryJoin.get("name"), categoryName);
        };
    }
}
