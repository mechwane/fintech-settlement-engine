package com.fintech.settlement_service.repository;

import com.fintech.settlement_service.model.OutboxEvent;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
public interface OutboxEventRepository extends JpaRepository<OutboxEvent, Long> {

    @Modifying
    @Query(value = """
            INSERT INTO outbox_events (aggregate_id, event_key, topic, payload_json, status, created_at)
            VALUES (:aggregateId, :eventKey, :topic, :payloadJson, 'PENDING', NOW())
            ON CONFLICT (event_key) DO NOTHING
            """, nativeQuery = true)
    int insertOutboxEvent(
            @Param("aggregateId") String aggregateId,
            @Param("eventKey") String eventKey,
            @Param("topic") String topic,
            @Param("payloadJson") String payloadJson
    );

    @Query(value = "SELECT * FROM outbox_events WHERE status = 'PENDING' ORDER BY id ASC LIMIT 50 FOR UPDATE SKIP LOCKED", nativeQuery = true)
    List<OutboxEvent> claimPendingBatch();

    @Modifying
    @Query(value = "UPDATE outbox_events SET status = 'PUBLISHED', published_at = NOW() WHERE id = :id", nativeQuery = true)
    void markPublished(@Param("id") Long id);
}