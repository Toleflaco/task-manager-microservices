package com.mtole.task.categories;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface CategoryRepository extends JpaRepository<Category, Long> {

    Optional<Category> findByIdAndUserId(Long id,Long userId);
    Page<Category> findAllByUserId(Long userId, Pageable pageable);
    long countByUserId(Long userId);
    long deleteByIdAndUserId(Long id, Long userId);

}
