package com.devcordeiro.tinylink.controller;

import com.devcordeiro.tinylink.dto.ShortenUrlRequest;
import com.devcordeiro.tinylink.dto.ShortenUrlResponse;
import com.devcordeiro.tinylink.dto.UrlAnalyticsResponse;
import com.devcordeiro.tinylink.dto.UrlStatsResponse;
import com.devcordeiro.tinylink.exception.RateLimitExceededException;
import com.devcordeiro.tinylink.exception.ShortCodeNotFoundException;
import com.devcordeiro.tinylink.service.RateLimitService;
import com.devcordeiro.tinylink.service.UrlShortenerService;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api")
public class UrlShortenerController {

    private final UrlShortenerService urlShortenerService;
    private final RateLimitService rateLimitService;

    public UrlShortenerController(UrlShortenerService urlShortenerService, RateLimitService rateLimitService) {
        this.urlShortenerService = urlShortenerService;
        this.rateLimitService = rateLimitService;
    }

    @PostMapping("/shorten")
    public ShortenUrlResponse shorten(
            @Valid @RequestBody ShortenUrlRequest request,
            HttpServletRequest httpRequest) {
        String clientIp = getClientIp(httpRequest);
        if(!rateLimitService.isAllowed(clientIp)) {
            throw new RateLimitExceededException(
                    rateLimitService.getRemainingRequests(clientIp),
                    rateLimitService.getTimeUntilReset(clientIp));
        }

        return urlShortenerService.shortenUrl(request, clientIp);
    }

    @GetMapping("/{shortCode}")
    public ResponseEntity<Void> redirectToUrl(@PathVariable String shortCode, HttpServletRequest httpRequest) {
        String originalUrl = urlShortenerService.getOriginalUrl(shortCode)
                .orElseThrow(() -> new ShortCodeNotFoundException(shortCode));

        String clientIp = getClientIp(httpRequest);
        String userAgent = httpRequest.getHeader("User-Agent");
        String referer = httpRequest.getHeader("Referer");
        urlShortenerService.recordClick(shortCode, clientIp, userAgent, referer);

        return ResponseEntity.status(HttpStatus.FOUND)
                .header(HttpHeaders.LOCATION, originalUrl)
                .build();
    }

    @GetMapping("/stats/{shortCode}")
    public UrlStatsResponse getStats(@PathVariable String shortCode) {
        return urlShortenerService.getUrlStats(shortCode)
                .orElseThrow(() -> new ShortCodeNotFoundException(shortCode));
    }

    @GetMapping("/analytics/{shortCode}")
    public UrlAnalyticsResponse getUrlAnalytics(@PathVariable String shortCode) {
        return urlShortenerService.getUrlAnalytics(shortCode)
                .orElseThrow(() -> new ShortCodeNotFoundException(shortCode));
    }

    @DeleteMapping("/{shortCode}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void deleteUrl(@PathVariable String shortCode) {
        if(!urlShortenerService.deleteUrl(shortCode)) {
            throw new ShortCodeNotFoundException(shortCode);
        }
    }

    private String getClientIp(HttpServletRequest httpRequest) {
        String xForwardedFor = httpRequest.getHeader("X-Forwarded-For");
        if(xForwardedFor != null && !xForwardedFor.isEmpty()) {
            return xForwardedFor.split(",")[0].trim();
        }

        String xRealIp = httpRequest.getHeader("X-Real-IP");
        if(xRealIp != null && !xRealIp.isEmpty()) {
            return xRealIp;
        }

        return httpRequest.getRemoteAddr();
    }
}
