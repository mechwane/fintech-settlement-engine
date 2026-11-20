package com.fintech.settlement_service.config;

import org.apache.kafka.common.TopicPartition;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.BeansException;
import org.springframework.beans.factory.config.BeanPostProcessor;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.kafka.config.ConcurrentKafkaListenerContainerFactory;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.listener.CommonErrorHandler;
import org.springframework.kafka.listener.DeadLetterPublishingRecoverer;
import org.springframework.kafka.listener.DefaultErrorHandler;
import org.springframework.util.backoff.FixedBackOff;

@Configuration
public class KafkaReliabilityConfig {

    private static final Logger log = LoggerFactory.getLogger(KafkaReliabilityConfig.class);

    @Bean
    public CommonErrorHandler kafkaErrorHandler(KafkaTemplate<String, Object> kafkaTemplate) {
        DeadLetterPublishingRecoverer recoverer = new DeadLetterPublishingRecoverer(
                kafkaTemplate,
                (record, ex) -> {
                    String dltTopic = record.topic() + ".DLT";
                    log.error("[DLT ROUTED] Exhausted retries for key '{}' on topic '{}' (offset {}). Quarantining to '{}' -> {}",
                            record.key(), record.topic(), record.offset(), dltTopic, ex.getMessage());
                    return new TopicPartition(dltTopic, record.partition());
                }
        );

        DefaultErrorHandler errorHandler = new DefaultErrorHandler(recoverer, new FixedBackOff(1000L, 2L));
        errorHandler.setRetryListeners((record, ex, attempt) ->
                log.warn("[KAFKA RETRY] Attempt {} failed for key '{}' on topic '{}': {}",
                        attempt, record.key(), record.topic(), ex.getMessage())
        );

        return errorHandler;
    }

    @Bean
    public BeanPostProcessor kafkaContainerFactoryBinder(CommonErrorHandler kafkaErrorHandler) {
        return new BeanPostProcessor() {
            @Override
            public Object postProcessAfterInitialization(Object bean, String beanName) throws BeansException {
                if (bean instanceof ConcurrentKafkaListenerContainerFactory<?, ?> factory) {
                    factory.setCommonErrorHandler(kafkaErrorHandler);
                }
                return bean;
            }
        };
    }
}