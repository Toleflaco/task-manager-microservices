package com.mtole.task.outbox;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.UUID;

public interface OutboxRepository extends JpaRepository<OutboxEvent, UUID> {

    @Query(nativeQuery = true, value = """
     SELECT * FROM outbox_events
     WHERE published_at IS NULL
     ORDER BY created_at ASC
     LIMIT :limit
     FOR UPDATE SKIP LOCKED
""")
    List<OutboxEvent> findPendingWithLock(@Param("limit") int limit);
}
