package com.fintech.settlement_service.model;

import jakarta.persistence.*;
import java.time.Instant;

@Entity
@Table(name = "dead_letter_events")
public class DeadLetterEvent {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "original_topic", nullable = false)
    private String originalTopic;

    @Column(name = "dlt_topic", nullable = false)
    private String dltTopic;

    @Column(name = "partition_id")
    private Integer partitionId;

    @Column(name = "offset_id")
    private Long offsetId;

    @Column(name = "event_key")
    private String eventKey;

    @Column(name = "payload_json", columnDefinition = "TEXT")
    private String payloadJson;

    @Column(name = "error_message", columnDefinition = "TEXT")
    private String errorMessage;

    @Column(name = "failed_at", nullable = false)
    private Instant failedAt;

    public DeadLetterEvent() {}

    public DeadLetterEvent(
            String originalTopic,
            String dltTopic,
            Integer partitionId,
            Long offsetId,
            String eventKey,
            String payloadJson,
            String errorMessage) {
        this.originalTopic = originalTopic;
        this.dltTopic = dltTopic;
        this.partitionId = partitionId;
        this.offsetId = offsetId;
        this.eventKey = eventKey;
        this.payloadJson = payloadJson;
        this.errorMessage = errorMessage;
        this.failedAt = Instant.now();
    }

    public Long getId() { return id; }
    public String getOriginalTopic() { return originalTopic; }
    public String getDltTopic() { return dltTopic; }
    public Integer getPartitionId() { return partitionId; }
    public Long getOffsetId() { return offsetId; }
    public String getEventKey() { return eventKey; }
    public String getPayloadJson() { return payloadJson; }
    public String getErrorMessage() { return errorMessage; }
    public Instant getFailedAt() { return failedAt; }
}