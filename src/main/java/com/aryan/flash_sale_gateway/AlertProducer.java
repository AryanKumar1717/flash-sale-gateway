package com.aryan.flash_sale_gateway;

import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.stereotype.Service;

@Service
public class AlertProducer {

    private final KafkaTemplate<String, String> kafkaTemplate;

    public AlertProducer(KafkaTemplate<String, String> kafkaTemplate) {
        this.kafkaTemplate = kafkaTemplate;
    }

    public void publishDegradedAlert(String orderId) {
        String message = "ALERT: Payment service degraded — circuit breaker OPEN (triggered by order: " + orderId + ")";
        kafkaTemplate.send("system-alerts", message);
    }
}