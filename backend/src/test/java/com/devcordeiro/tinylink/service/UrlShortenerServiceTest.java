package com.devcordeiro.tinylink.service;

import com.devcordeiro.tinylink.dto.ShortenUrlRequest;
import com.devcordeiro.tinylink.dto.UrlStatsResponse;
import com.devcordeiro.tinylink.exception.AliasAlreadyExistsException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.data.redis.core.ValueOperations;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.entry;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class UrlShortenerServiceTest {

    /** A clock the test can move forward, to cross expiry dates without waiting. */
    private static final class MutableClock extends Clock {
        private volatile Instant now;

        MutableClock(LocalDateTime start) {
            this.now = start.toInstant(ZoneOffset.UTC);
        }

        void advance(Duration duration) {
            now = now.plus(duration);
        }

        @Override
        public ZoneId getZone() {
            return ZoneOffset.UTC;
        }

        @Override
        public Clock withZone(ZoneId zone) {
            throw new UnsupportedOperationException();
        }

        @Override
        public Instant instant() {
            return now;
        }
    }

    private static final LocalDateTime START = LocalDateTime.of(2026, 1, 15, 9, 30);

    private final MutableClock clock = new MutableClock(START);
    private UrlShortenerService service;

    @BeforeEach
    @SuppressWarnings("unchecked")
    void setUp() {
        RedisTemplate<String, Object> redisTemplate = mock(RedisTemplate.class);
        when(redisTemplate.opsForValue()).thenReturn(mock(ValueOperations.class));

        service = new UrlShortenerService(redisTemplate, clock, "http://localhost:8080", 6, 10, 30);
    }

    private static ShortenUrlRequest request(String alias) {
        return ShortenUrlRequest.builder().originalUrl("https://example.com").customAlias(alias).build();
    }

    private String shortenExpiringIn(Duration duration) {
        ShortenUrlRequest request = ShortenUrlRequest.builder()
                .originalUrl("https://example.com")
                .expiresAt(duration == null ? null : START.plus(duration))
                .build();
        return service.shortenUrl(request, "1.1.1.1").getShortCode();
    }

    private boolean isActive(String code) {
        return service.getUrlStats(code).orElseThrow().isActive();
    }

    @Test
    void linkStopsRedirectingOnceItsExpiryHasPassed() {
        String code = shortenExpiringIn(Duration.ofHours(1));
        assertThat(service.getOriginalUrl(code)).contains("https://example.com");

        clock.advance(Duration.ofHours(2));

        assertThat(service.getOriginalUrl(code)).isEmpty();
        assertThat(isActive(code)).isFalse();
    }

    @Test
    void cleanupDeactivatesOnlyLinksPastTheirExpiry() {
        String expired = shortenExpiringIn(Duration.ofHours(1));
        String stillValid = shortenExpiringIn(Duration.ofHours(3));
        String neverExpires = shortenExpiringIn(null);

        clock.advance(Duration.ofHours(2));
        service.cleanupExpiredUrls();

        assertThat(isActive(expired)).isFalse();
        assertThat(isActive(stillValid)).isTrue();
        assertThat(isActive(neverExpires)).isTrue();
    }

    @Test
    void clicksByHourUsesZeroPaddedHoursInChronologicalOrder() {
        String code = shortenExpiringIn(null);
        service.recordClick(code, "10.0.0.1", "agent", null);
        clock.advance(Duration.ofHours(1));
        service.recordClick(code, "10.0.0.1", "agent", null);
        service.recordClick(code, "10.0.0.1", "agent", null);

        assertThat(service.getUrlAnalytics(code)).get().satisfies(analytics ->
                assertThat(analytics.getClicksByHour()).containsExactly(
                        entry("09:00", 1),
                        entry("10:00", 2)));
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
