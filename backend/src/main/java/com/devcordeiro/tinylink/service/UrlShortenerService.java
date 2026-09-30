package com.devcordeiro.tinylink.service;

import com.devcordeiro.tinylink.dto.ShortenUrlRequest;
import com.devcordeiro.tinylink.dto.ShortenUrlResponse;
import com.devcordeiro.tinylink.dto.UrlAnalyticsResponse;
import com.devcordeiro.tinylink.dto.UrlStatsResponse;
import com.devcordeiro.tinylink.exception.AliasAlreadyExistsException;
import com.devcordeiro.tinylink.model.ClickEvent;
import com.devcordeiro.tinylink.model.UrlData;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ThreadLocalRandom;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
@Slf4j
public class UrlShortenerService {

    private final RedisTemplate<String, Object> redisTemplate;

    private final Map<String, UrlData> urlMappings = new ConcurrentHashMap<>();
    private final Map<String, List<ClickEvent>> clickAnalytics = new ConcurrentHashMap<>();

    @Value("${tinylink.base-url}")
    private String baseUrl;

    @Value("${tinylink.short-code.length}")
    private int shortCodeLength;

    @Value("${tinylink.short-code.max-attempts}")
    private int maxGenerationAttempts;

    @Value("${tinylink.cache.ttl-minutes}")
    private int cacheTtlMinutes;

    private static final String BASE_62_CHARS = "0123456789abcdefghijklmnopqrstuvwxyzABCDEFGHIJKLMNOPQRSTUVWXYZ";

    public ShortenUrlResponse shortenUrl(ShortenUrlRequest request, String clientIp) {
        String customAlias = request.getCustomAlias();
        UrlData urlData;

        if (customAlias == null || customAlias.isBlank()) {
            urlData = storeWithGeneratedShortCode(request, clientIp);
        } else {
            urlData = newUrlData(customAlias.trim(), request, clientIp);
            // putIfAbsent makes check-and-insert atomic, so two requests racing for one alias can't both win.
            if (urlMappings.putIfAbsent(urlData.getShortCode(), urlData) != null) {
                throw new AliasAlreadyExistsException(urlData.getShortCode());
            }
        }

        String shortCode = urlData.getShortCode();
        clickAnalytics.put(shortCode, new ArrayList<>());

        cacheUrl(shortCode, request.getOriginalUrl(), request.getExpiresAt());

        log.info("Created short URL: {} -> {}", shortCode, request.getOriginalUrl());

        return ShortenUrlResponse.builder()
                .shortUrl(buildShortUrl(shortCode))
                .shortCode(shortCode)
                .originalUrl(request.getOriginalUrl())
                .createdAt(urlData.getCreatedAt())
                .expiresAt(urlData.getExpiresAt())
                .build();
    }

    private UrlData newUrlData(String shortCode, ShortenUrlRequest request, String clientIp) {
        return UrlData.builder()
                .originalUrl(request.getOriginalUrl())
                .shortCode(shortCode)
                .expiresAt(request.getExpiresAt())
                .createdAt(LocalDateTime.now())
                .createdBy(clientIp)
                .clickCount(0)
                .isActive(true)
                .clickEvents(new ArrayList<>())
                .build();
    }

    private String buildShortUrl(String shortCode) {
        String normalizedBaseUrl = baseUrl.endsWith("/") ? baseUrl.substring(0, baseUrl.length() -1) : baseUrl;
        return normalizedBaseUrl + "/api/" + shortCode;
    }

    private void cacheUrl(String shortCode, String originalUrl, LocalDateTime expiresAt) {
        // A cache hit skips the expiry check, so the entry must never outlive the link.
        Duration ttl = Duration.ofMinutes(cacheTtlMinutes);
        if (expiresAt != null) {
            Duration untilExpiry = Duration.between(LocalDateTime.now(), expiresAt);
            if (untilExpiry.isNegative() || untilExpiry.isZero()) {
                return;
            }
            if (untilExpiry.compareTo(ttl) < 0) {
                ttl = untilExpiry;
            }
        }

        try {
            redisTemplate.opsForValue().set("url:" + shortCode, originalUrl, ttl);
        }  catch (Exception e) {
            log.warn("Failed to cache url for: {}:{}", shortCode, e.getMessage());
        }
    }

    private UrlData storeWithGeneratedShortCode(ShortenUrlRequest request, String clientIp) {
        for (int attempt = 0; attempt < maxGenerationAttempts; attempt++) {
            UrlData urlData = newUrlData(generateRandomBase62(), request, clientIp);
            if (urlMappings.putIfAbsent(urlData.getShortCode(), urlData) == null) {
                return urlData;
            }
        }

        throw new IllegalStateException("Failed to generate unique short code after " + maxGenerationAttempts + " attempts");
    }

    private String generateRandomBase62() {
        StringBuilder sb = new StringBuilder(shortCodeLength);
        for (int i = 0; i < shortCodeLength; i++) {
            int index = ThreadLocalRandom.current().nextInt(BASE_62_CHARS.length());

            sb.append(BASE_62_CHARS.charAt(index));
        }

        return sb.toString();
    }

    public Optional<String> getOriginalUrl(String shortCode) {
        String cachedUrl = getCachedUrl(shortCode);
        if (cachedUrl != null) {
            return Optional.of(cachedUrl);
        }

        UrlData urlData = urlMappings.get(shortCode);
        if (urlData != null && urlData.isActive()) {
            if(isExpired(urlData)) {
                urlData.setActive(false);
                return Optional.empty();
            }
            cacheUrl(shortCode, urlData.getOriginalUrl(), urlData.getExpiresAt());
            return Optional.of(urlData.getOriginalUrl());
        }
        return Optional.empty();
    }

    private boolean isExpired(UrlData urlData) {
        return urlData.getExpiresAt() != null && urlData.getExpiresAt().isBefore(LocalDateTime.now());
    }

    private String getCachedUrl(String shortCode) {
        try {
            return (String) redisTemplate.opsForValue().get("url:" + shortCode);
        } catch (Exception e) {
            log.warn("Failed to get cached url for: {}:{}", shortCode, e.getMessage());
            return null;
        }
    }

    public void recordClick(String shortCode, String clientIp, String userAgent, String referer) {
        UrlData urlData = urlMappings.get(shortCode);
        if (urlData != null && urlData.isActive()) {
            urlData.setClickCount(urlData.getClickCount() + 1);

            ClickEvent clickEvent = ClickEvent.builder()
                    .timestamp(LocalDateTime.now())
                    .ipAddress(clientIp)
                    .userAgent(userAgent)
                    .referer(referer)
                    .build();

            clickAnalytics.get(shortCode).add(clickEvent);
            log.debug("Clicked URL: {} -> {}", shortCode, urlData.getOriginalUrl());
        }
    }

    public Optional<UrlStatsResponse> getUrlStats(String shortCode) {
        UrlData urlData = urlMappings.get(shortCode);
        if (urlData == null) {
            return Optional.empty();
        }

        return Optional.of(
                UrlStatsResponse.builder()
                        .shortCode(shortCode)
                        .createdAt(urlData.getCreatedAt())
                        .expiresAt(urlData.getExpiresAt())
                        .originalUrl(urlData.getOriginalUrl())
                        .clickCount(urlData.getClickCount())
                        .isActive(urlData.isActive())
                        .createdBy(urlData.getCreatedBy())
                        .build()
        );
    }

    public Optional<UrlAnalyticsResponse> getUrlAnalytics(String shortCode) {

        UrlData urlData = urlMappings.get(shortCode);
        if (urlData == null) {
            return Optional.empty();
        }

        List<ClickEvent> clickEvents = clickAnalytics.getOrDefault(shortCode, new ArrayList<>());

        Map<String, Integer> clicksByReferer = clickEvents.stream()
                .filter(c -> c.getReferer() != null)
                .collect(Collectors.groupingBy(
                        ClickEvent::getReferer, Collectors.summingInt( e -> 1)
                ));

        Map<String, Integer> clicksByHour = clickEvents.stream()
                .collect(Collectors.groupingBy(
                        c -> c.getTimestamp().getHour() + ":00",
                        Collectors.summingInt( e -> 1)
                ));

        Map<String, Integer> clicksByDay = clickEvents.stream()
                .collect(Collectors.groupingBy(
                        c -> c.getTimestamp().toLocalDate().toString(),
                        Collectors.summingInt( e -> 1)
                ));

        List<ClickEvent> recentClicks = clickEvents.stream()
                .sorted(( a, b) -> b.getTimestamp().compareTo(a.getTimestamp()))
                .limit(10)
                .toList();


        return Optional.of(
                UrlAnalyticsResponse.builder()
                        .shortCode(shortCode)
                        .createdAt(urlData.getCreatedAt())
                        .expiresAt(urlData.getExpiresAt())
                        .originalUrl(urlData.getOriginalUrl())
                        .totalClicks(urlData.getClickCount())
                        .recentClicks(recentClicks)
                        .clicksByDay(clicksByDay)
                        .clicksByHour(clicksByHour)
                        .clicksByReferer(clicksByReferer)
                        .build()
        );
    }

    public boolean deleteUrl(String shortCode) {
        UrlData urlData = urlMappings.get(shortCode);
        if (urlData != null && urlData.isActive()) {
            urlData.setActive(false);
            deleteCacheUrl(shortCode);
            log.info("Deleted URL: {} -> {}", shortCode, urlData.getOriginalUrl());
            return true;
        }
        return false;
    }

    private void deleteCacheUrl(String shortCode) {
        try {
            redisTemplate.delete("url:" + shortCode);
        } catch (Exception e) {
            log.warn("Failed to delete URL: {} -> {}", shortCode, e.getMessage());
        }
    }

    public void cleanupExpiredUrls() {
        int cleanedCount = 0;
        LocalDateTime now = LocalDateTime.now();

        for (Map.Entry<String, UrlData> entry : urlMappings.entrySet()) {
            UrlData urlData = entry.getValue();
            if (urlData.getExpiresAt() != null && urlData.getExpiresAt().isBefore(now) && urlData.isActive()) {
                urlData.setActive(false);
                deleteCacheUrl(entry.getKey());
                cleanedCount++;
            }
        }

        if (cleanedCount > 0) {
            log.info("Cleaned expired URLs: {} -> {}", cleanedCount, urlMappings.size());
        }
    }
}
