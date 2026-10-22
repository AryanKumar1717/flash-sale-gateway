package com.aryan.flash_sale_gateway;

import io.github.resilience4j.circuitbreaker.annotation.CircuitBreaker;
import org.springframework.stereotype.Service;

@Service
public class OrderService {

    private final PaymentClient paymentClient;
    private final AlertProducer alertProducer;

    public OrderService(PaymentClient paymentClient, AlertProducer alertProducer) {
        this.paymentClient = paymentClient;
        this.alertProducer = alertProducer;
    }

    @CircuitBreaker(name = "paymentService", fallbackMethod = "paymentFallback")
    public String placeOrder(String orderId) {
        return paymentClient.callPaymentService(orderId);
    }

    public String paymentFallback(String orderId, Throwable t) {
        alertProducer.publishDegradedAlert(orderId);
        return "Payment service unavailable right now. Please try again shortly. (Order: " + orderId + ")";
    }
}