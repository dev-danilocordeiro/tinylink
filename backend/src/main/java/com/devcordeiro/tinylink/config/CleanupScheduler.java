package com.devcordeiro.tinylink.config;

import com.devcordeiro.tinylink.service.UrlShortenerService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Component
@RequiredArgsConstructor
@Slf4j
public class CleanupScheduler {
    private final UrlShortenerService urlShortenerService;

    @Scheduled(fixedRateString = "#{${tinylink.cleanup.interval-minutes} * 60 * 1000}")
    public void cleanupExpiredUrls() {
        try {
            log.debug("Running scheduled cleanup of expired URLs");
            urlShortenerService.cleanupExpiredUrls();
        } catch (Exception e) {
            log.error("Error while cleaning expired URLs", e);
        }
    }
}
