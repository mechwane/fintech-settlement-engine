package com.fintech.settlement_service.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fintech.settlement_service.dto.SettlementEvent;
import com.fintech.settlement_service.model.OutboxEvent;
import com.fintech.settlement_service.repository.OutboxEventRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

@Component
public class OutboxRelayWorker {

    private static final Logger log = LoggerFactory.getLogger(OutboxRelayWorker.class);

    private final OutboxEventRepository outboxRepository;
    private final KafkaTemplate<String, Object> kafkaTemplate;
    private final ObjectMapper objectMapper = new ObjectMapper();

    public OutboxRelayWorker(
            OutboxEventRepository outboxRepository,
            KafkaTemplate<String, Object> kafkaTemplate) {
        this.outboxRepository = outboxRepository;
        this.kafkaTemplate = kafkaTemplate;
    }

    @Transactional
    @Scheduled(fixedDelay = 500)
    public void relayPendingEvents() {
        List<OutboxEvent> batch = outboxRepository.claimPendingBatch();
        for (OutboxEvent row : batch) {
            try {
                SettlementEvent event = objectMapper.readValue(row.getPayloadJson(), SettlementEvent.class);
                // Synchronous .get() guarantees Kafka broker ack before marking PUBLISHED
                kafkaTemplate.send(row.getTopic(), row.getAggregateId(), event).get();
                outboxRepository.markPublished(row.getId());
                log.info("[OUTBOX RELAY] Published Outbox ID {} -> Kafka Topic '{}' (Key: {})",
                        row.getId(), row.getTopic(), row.getAggregateId());
            } catch (Exception ex) {
                log.error("[OUTBOX RELAY] Kafka unavailable for Outbox ID {}. Will retry on next tick: {}",
                        row.getId(), ex.getMessage());
                break;
            }
        }
    }
}