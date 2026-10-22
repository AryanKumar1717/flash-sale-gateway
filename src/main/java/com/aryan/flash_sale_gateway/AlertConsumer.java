package com.aryan.flash_sale_gateway;

import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;

@Component
public class AlertConsumer {

    @KafkaListener(topics = "system-alerts", groupId = "alerting-group")
    public void consume(String message) {
        System.out.println("🔔 " + message);
    }
}