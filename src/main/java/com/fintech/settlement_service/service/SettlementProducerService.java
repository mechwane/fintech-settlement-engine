package com.fintech.settlement_service.service;

import com.fintech.settlement_service.dto.SettlementEvent;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.stereotype.Service;

@Service
public class SettlementProducerService {

    private static final Logger log = LoggerFactory.getLogger(SettlementProducerService.class);
    private static final String TOPIC = "virtual-account-settlements";

    private final KafkaTemplate<String, Object> kafkaTemplate;

    public SettlementProducerService(KafkaTemplate<String, Object> kafkaTemplate) {
        this.kafkaTemplate = kafkaTemplate;
    }

    public void queueSettlement(SettlementEvent event) {
        log.info("Queueing settlement event to Kafka. Ref: {} | Partition Key (Merchant): {}",
                event.transactionReference(), event.merchantId());
        this.kafkaTemplate.send(TOPIC, event.merchantId(), event);
    }
}