package com.aryan.flash_sale_gateway;

import org.springframework.web.bind.annotation.*;

@RestController
public class OrderController {

    private final RateLimiterService rateLimiterService;
    private final OrderService orderService;

    public OrderController(RateLimiterService rateLimiterService, OrderService orderService) {
        this.rateLimiterService = rateLimiterService;
        this.orderService = orderService;
    }

    @PostMapping("/checkout")
    public String checkout(@RequestParam String orderId) {
        boolean allowed = rateLimiterService.isAllowed(orderId, 5, 1);

        if (!allowed) {
            return "Rate limit exceeded. Please slow down.";
        }

        return orderService.placeOrder(orderId);
    }
}