package com.devcordeiro.tinylink.service;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.redis.test.autoconfigure.DataRedisTest;
import org.springframework.context.annotation.Import;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.test.context.TestPropertySource;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Runs against the Redis from docker-compose, like the application context test.
 */
@DataRedisTest
@Import(RateLimitService.class)
@TestPropertySource(properties = {
        "tinylink.rate-limit.requests-per-minute=5",
        "tinylink.rate-limit.requests-per-hour=8"
})
class RateLimitServiceTest {

    @Autowired
    private RateLimitService rateLimitService;

    @Autowired
    private StringRedisTemplate redisTemplate;

    private final String clientIp = "test-" + UUID.randomUUID();

    @AfterEach
    void deleteCounters() {
        redisTemplate.delete(List.of("ratelimit:" + clientIp + ":minute", "ratelimit:" + clientIp + ":hour"));
    }

    @Test
    void allowsRequestsUpToTheMinuteLimitAndThenRejectsUntilTheWindowResets() {
        for (int i = 4; i >= 0; i--) {
            RateLimitService.Decision decision = rateLimitService.tryAcquire(clientIp);
            assertThat(decision.allowed()).isTrue();
            assertThat(decision.remainingRequests()).isEqualTo(i);
        }

        RateLimitService.Decision rejected = rateLimitService.tryAcquire(clientIp);
        assertThat(rejected.allowed()).isFalse();
        assertThat(rejected.remainingRequests()).isZero();
        assertThat(rejected.secondsUntilReset()).isBetween(1L, 60L);
    }

    @Test
    void rejectedRequestsDoNotCountTowardsTheHourLimit() {
        for (int i = 0; i < 20; i++) {
            rateLimitService.tryAcquire(clientIp);
        }

        assertThat(redisTemplate.opsForValue().get("ratelimit:" + clientIp + ":hour")).isEqualTo("5");
    }

    @Test
    void hourLimitRejectsOnceReachedEvenWithMinuteQuotaLeft() {
        redisTemplate.opsForValue().set("ratelimit:" + clientIp + ":hour", "8", Duration.ofMinutes(30));

        RateLimitService.Decision rejected = rateLimitService.tryAcquire(clientIp);
        assertThat(rejected.allowed()).isFalse();
        assertThat(rejected.secondsUntilReset()).isGreaterThan(60L);
    }

    @Test
    void concurrentRequestsFromOneClientNeverExceedTheLimit() throws Exception {
        int threads = 50;
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        CountDownLatch start = new CountDownLatch(1);

        List<Future<Boolean>> futures = new ArrayList<>();
        for (int i = 0; i < threads; i++) {
            futures.add(pool.submit(() -> {
                start.await();
                return rateLimitService.tryAcquire(clientIp).allowed();
            }));
        }
        start.countDown();
        int allowed = 0;
        for (Future<Boolean> f : futures) {
            if (f.get()) {
                allowed++;
            }
        }
        pool.shutdown();

        assertThat(allowed).isEqualTo(5);
    }
}
