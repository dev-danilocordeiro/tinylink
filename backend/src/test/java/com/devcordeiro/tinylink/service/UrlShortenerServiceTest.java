package com.devcordeiro.tinylink.service;

import com.devcordeiro.tinylink.dto.ShortenUrlRequest;
import com.devcordeiro.tinylink.dto.UrlStatsResponse;
import com.devcordeiro.tinylink.exception.AliasAlreadyExistsException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.data.redis.core.ValueOperations;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class UrlShortenerServiceTest {

    private UrlShortenerService service;

    @BeforeEach
    @SuppressWarnings("unchecked")
    void setUp() {
        RedisTemplate<String, Object> redisTemplate = mock(RedisTemplate.class);
        when(redisTemplate.opsForValue()).thenReturn(mock(ValueOperations.class));

        service = new UrlShortenerService(redisTemplate);
        ReflectionTestUtils.setField(service, "baseUrl", "http://localhost:8080");
        ReflectionTestUtils.setField(service, "shortCodeLength", 6);
        ReflectionTestUtils.setField(service, "maxGenerationAttempts", 10);
        ReflectionTestUtils.setField(service, "cacheTtlMinutes", 30);
    }

    private static ShortenUrlRequest request(String alias) {
        return ShortenUrlRequest.builder().originalUrl("https://example.com").customAlias(alias).build();
    }

    @Test
    void reusingAnAliasIsRejected() {
        service.shortenUrl(request("my-link"), "1.1.1.1");

        assertThatThrownBy(() -> service.shortenUrl(request("my-link"), "2.2.2.2"))
                .isInstanceOf(AliasAlreadyExistsException.class);
    }

    @Test
    void onlyOneOfManyConcurrentRequestsForTheSameAliasSucceeds() throws Exception {
        int threads = 32;
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        CountDownLatch start = new CountDownLatch(1);
        AtomicInteger created = new AtomicInteger();
        AtomicInteger rejected = new AtomicInteger();

        List<Future<?>> futures = new ArrayList<>();
        for (int i = 0; i < threads; i++) {
            String ip = "10.0.0." + i;
            futures.add(pool.submit(() -> {
                start.await();
                try {
                    service.shortenUrl(request("contested"), ip);
                    created.incrementAndGet();
                } catch (AliasAlreadyExistsException e) {
                    rejected.incrementAndGet();
                }
                return null;
            }));
        }
        start.countDown();
        for (Future<?> f : futures) {
            f.get();
        }
        pool.shutdown();

        assertThat(created).hasValue(1);
        assertThat(rejected).hasValue(threads - 1);
    }

    @Test
    void concurrentClicksAreAllCountedAndShowUpInAnalytics() throws Exception {
        String code = service.shortenUrl(request(null), "1.1.1.1").getShortCode();
        int threads = 16;
        int clicksPerThread = 2_000;
        ExecutorService pool = Executors.newFixedThreadPool(threads + 1);
        CountDownLatch start = new CountDownLatch(1);

        List<Future<?>> futures = new ArrayList<>();
        for (int i = 0; i < threads; i++) {
            futures.add(pool.submit(() -> {
                start.await();
                for (int c = 0; c < clicksPerThread; c++) {
                    service.recordClick(code, "10.0.0.1", "agent", "https://ref.example");
                }
                return null;
            }));
        }
        // Reading analytics while clicks are written must not throw ConcurrentModificationException.
        futures.add(pool.submit(() -> {
            start.await();
            for (int r = 0; r < 200; r++) {
                service.getUrlAnalytics(code);
            }
            return null;
        }));
        start.countDown();
        for (Future<?> f : futures) {
            f.get();
        }
        pool.shutdown();

        int expected = threads * clicksPerThread;
        assertThat(service.getUrlStats(code)).get().extracting(UrlStatsResponse::getClickCount).isEqualTo(expected);
        assertThat(service.getUrlAnalytics(code)).get().satisfies(analytics -> {
            assertThat(analytics.getTotalClicks()).isEqualTo(expected);
            assertThat(analytics.getClicksByReferer()).containsEntry("https://ref.example", expected);
        });
    }

    @Test
    void deletingALinkTwiceOnlySucceedsTheFirstTime() {
        String code = service.shortenUrl(request(null), "1.1.1.1").getShortCode();

        assertThat(service.deleteUrl(code)).isTrue();
        assertThat(service.deleteUrl(code)).isFalse();
    }

    @Test
    void deletingAnUnknownLinkFails() {
        assertThat(service.deleteUrl("nope")).isFalse();
    }
}
