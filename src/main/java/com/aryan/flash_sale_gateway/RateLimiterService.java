package com.aryan.flash_sale_gateway;

import org.springframework.core.io.ClassPathResource;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.RedisScript;
import org.springframework.stereotype.Service;

import java.util.Collections;
import java.util.List;

@Service
public class RateLimiterService {

    private final StringRedisTemplate redisTemplate;
    private final RedisScript<List> tokenBucketScript;

    public RateLimiterService(StringRedisTemplate redisTemplate) {
        this.redisTemplate = redisTemplate;
        this.tokenBucketScript = RedisScript.of(
                new ClassPathResource("token_bucket.lua"), List.class);
    }

    public boolean isAllowed(String clientId, int capacity, double refillRate) {
        String key = "ratelimit:" + clientId;
        double now = System.currentTimeMillis() / 1000.0;

        List<Long> result = redisTemplate.execute(
                tokenBucketScript,
                Collections.singletonList(key),
                String.valueOf(capacity),
                String.valueOf(refillRate),
                String.valueOf(now),
                "1"
        );

        return result.get(0) == 1;
    }
}