package com.devcordeiro.tinylink.service;

import com.devcordeiro.tinylink.model.RateLimitData;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.catalina.util.RateLimiter;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.stereotype.Service;
import tools.jackson.databind.ObjectMapper;

import java.time.LocalDateTime;
import java.time.temporal.ChronoUnit;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;

@Service
@RequiredArgsConstructor
@Slf4j
public class RateLimitService {

    private final RedisTemplate<String, Object> redisTemplate;
    private final ObjectMapper objectMapper;

    @Value("${tinylink.rate-limit.requests-per-minute}")
    private int requestsPerMinute;

    @Value("${tinylink.rate-limit.requests-per-hour}")
    private int requestsPerHour;

    private final ConcurrentHashMap<String, RateLimitData> rateLimitData = new ConcurrentHashMap<>();

    private static final String REDIS_KEY_PREFIX = "ratelimit:";

    public boolean isAllowed(String clientIp) {
        String key = REDIS_KEY_PREFIX + clientIp;

        LocalDateTime now = LocalDateTime.now();

        RateLimitData data = getRateLimitDataFromRedis(key);

        if(data == null) {
            data = rateLimitData.computeIfAbsent(clientIp, k -> RateLimitData.builder()
                    .minuteCount(0)
                    .hourCount(0)
                    .minuteWindowStart(now)
                    .hourWindowStart(now)
                    .build());
        }

        if(isWithinMinuteWindow(data, now)) {
            if(data.getMinuteCount() >= requestsPerMinute) {
                log.warn("Minute Limit Exceeded for clientIp: {}", clientIp);
                return false;
            }
        } else {
            data.setMinuteCount(0);
            data.setMinuteWindowStart(now);
        }

        if(isWithinHourWindow(data, now)) {
            if(data.getHourCount() >= requestsPerHour) {
                log.warn("Hour Limit Exceeded for clientIp: {}", clientIp);
                return false;
            }
        } else {
            data.setHourCount(0);
            data.setHourWindowStart(now);
        }

        data.setMinuteCount(data.getMinuteCount() + 1);
        data.setHourCount(data.getHourCount() + 1);

        saveRateLimitDataToRedis(key, data);

        return true;
    }

    private boolean isWithinHourWindow(RateLimitData data, LocalDateTime now) {
        return data.getHourWindowStart() != null && ChronoUnit.HOURS.between(
                data.getHourWindowStart(), now
        ) < 1;
    }

    private boolean isWithinMinuteWindow(RateLimitData data, LocalDateTime now) {
        return data.getMinuteWindowStart() != null && ChronoUnit.MINUTES.between(
                data.getMinuteWindowStart(), now
        ) < 1;
    }

    private void saveRateLimitDataToRedis(String key, RateLimitData data) {
        try {
            redisTemplate.opsForValue().set(key, data, 1, TimeUnit.HOURS);
        } catch (Exception e) {
            log.warn("Failed to save rate limit data for clientIp: {}", key, e);
        }
    }

    private RateLimitData getRateLimitDataFromRedis(String key) {
        try {
            Object value = redisTemplate.opsForValue().get(key);
            if (value == null || value instanceof RateLimitData) {
                return (RateLimitData) value;
            }
            // The serializer stores no type info, so the value comes back as a Map.
            return objectMapper.convertValue(value, RateLimitData.class);
         } catch (Exception e) {
            log.warn("Failed to load rate limit data from redis {}", e.getMessage());
            return null;
        }
    }

    public int getRemainingRequests(String clientIp) {
        String key = REDIS_KEY_PREFIX + clientIp;
        RateLimitData data = getRateLimitDataFromRedis(key);

        if (data == null) {
            return requestsPerMinute;
        }

        LocalDateTime now = LocalDateTime.now();

        if(!isWithinMinuteWindow(data, now)) {
            return requestsPerMinute;
        }

        return Math.max(0, requestsPerMinute - data.getMinuteCount());
    }

    public long getTimeUntilReset(String clientIp) {
        String key = REDIS_KEY_PREFIX + clientIp;
        RateLimitData data = getRateLimitDataFromRedis(key);
        if (data == null) {
            return 0L;
        }

        LocalDateTime now = LocalDateTime.now();
        if(data.getMinuteCount() >= requestsPerMinute) {
            LocalDateTime nextMinute = data.getMinuteWindowStart().plusMinutes(1);

            return ChronoUnit.SECONDS.between(now, nextMinute);
        }

        if(data.getHourCount() >= requestsPerHour) {
            LocalDateTime nextHour = data.getHourWindowStart().plusHours(1);

            return ChronoUnit.SECONDS.between(now, nextHour);
        }

        return 0L;
    }
}
