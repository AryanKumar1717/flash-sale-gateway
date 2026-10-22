package com.aryan.flash_sale_gateway;

import org.springframework.stereotype.Service;
import org.springframework.web.client.RestTemplate;

@Service
public class PaymentClient {

    private final RestTemplate restTemplate = new RestTemplate();

    public String callPaymentService(String orderId) {
        String url = "http://localhost:8081/process-payment?orderId=" + orderId;
        return restTemplate.postForObject(url, null, String.class);
    }
}