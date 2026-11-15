package com.fintech.settlement_service.model;

import jakarta.persistence.*;
import java.time.Instant;

@Entity
@Table(name = "outbox_events")
public class OutboxEvent {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "aggregate_id", nullable = false, updatable = false)
    private String aggregateId;

    @Column(name = "event_key", nullable = false, unique = true, updatable = false)
    private String eventKey;

    @Column(name = "topic", nullable = false, updatable = false)
    private String topic;

    @Column(name = "payload_json", nullable = false, columnDefinition = "TEXT", updatable = false)
    private String payloadJson;

    @Column(name = "status", nullable = false)
    private String status;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "published_at")
    private Instant publishedAt;

    @PrePersist
    protected void onCreate() {
        this.createdAt = Instant.now();
    }

    public OutboxEvent() {}

    public OutboxEvent(String aggregateId, String eventKey, String topic, String payloadJson) {
        this.aggregateId = aggregateId;
        this.eventKey = eventKey;
        this.topic = topic;
        this.payloadJson = payloadJson;
        this.status = "PENDING";
    }

    public Long getId() { return id; }
    public String getAggregateId() { return aggregateId; }
    public String getEventKey() { return eventKey; }
    public String getTopic() { return topic; }
    public String getPayloadJson() { return payloadJson; }
    public String getStatus() { return status; }
}