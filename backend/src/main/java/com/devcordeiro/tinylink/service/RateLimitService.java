package com.devcordeiro.tinylink.service;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.io.ClassPathResource;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.RedisScript;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.util.List;

@Service
@Slf4j
public class RateLimitService {

    public record Decision(boolean allowed, int remainingRequests, long secondsUntilReset) {
    }

    private static final String REDIS_KEY_PREFIX = "ratelimit:";
    private static final Duration MINUTE = Duration.ofMinutes(1);
    private static final Duration HOUR = Duration.ofHours(1);

    @SuppressWarnings("rawtypes")
    private static final RedisScript<List> RATE_LIMIT_SCRIPT =
            RedisScript.of(new ClassPathResource("scripts/rate_limit.lua"), List.class);

    private final StringRedisTemplate redisTemplate;
    private final int requestsPerMinute;
    private final int requestsPerHour;

    public RateLimitService(
            StringRedisTemplate redisTemplate,
            @Value("${tinylink.rate-limit.requests-per-minute}") int requestsPerMinute,
            @Value("${tinylink.rate-limit.requests-per-hour}") int requestsPerHour) {
        this.redisTemplate = redisTemplate;
        this.requestsPerMinute = requestsPerMinute;
        this.requestsPerHour = requestsPerHour;
    }

    public Decision tryAcquire(String clientIp) {
        String key = REDIS_KEY_PREFIX + clientIp;
        List<?> result;
        try {
            result = redisTemplate.execute(
                    RATE_LIMIT_SCRIPT,
                    List.of(key + ":minute", key + ":hour"),
                    String.valueOf(requestsPerMinute),
                    String.valueOf(requestsPerHour),
                    String.valueOf(MINUTE.toMillis()),
                    String.valueOf(HOUR.toMillis()));
        } catch (Exception e) {
            // Fail open: an unavailable Redis shouldn't take link creation down with it.
            log.warn("Rate limit check failed for clientIp: {}, allowing request: {}", clientIp, e.getMessage());
            return new Decision(true, requestsPerMinute, 0);
        }

        boolean allowed = ((Number) result.get(0)).longValue() == 1;
        int remaining = ((Number) result.get(1)).intValue();
        long millisUntilReset = ((Number) result.get(2)).longValue();

        if (!allowed) {
            log.warn("Rate limit exceeded for clientIp: {}", clientIp);
        }
        return new Decision(allowed, remaining, Math.ceilDiv(Math.max(millisUntilReset, 0), 1000));
    }
}
