package com.devcordeiro.tinylink.controller;

import com.devcordeiro.tinylink.dto.ShortenUrlRequest;
import com.devcordeiro.tinylink.dto.ShortenUrlResponse;
import com.devcordeiro.tinylink.dto.UrlAnalyticsResponse;
import com.devcordeiro.tinylink.dto.UrlStatsResponse;
import com.devcordeiro.tinylink.service.RateLimitService;
import com.devcordeiro.tinylink.service.UrlShortenerService;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Service;
import org.springframework.web.bind.annotation.*;

import java.util.Map;
import java.util.Optional;

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
    public ResponseEntity<?> shorten(
            @Valid @RequestBody ShortenUrlRequest request,
            HttpServletRequest httpRequest) {
        String clientIp = getClientIp(httpRequest);
        if(!rateLimitService.isAllowed(clientIp)) {
            return ResponseEntity.status(HttpStatus.TOO_MANY_REQUESTS)
                    .body(Map.of(
                            "error", "Rate limit exceeded",
                            "remainingRequests", rateLimitService.getRemainingRequests(clientIp),
                            "timeUntilReset", rateLimitService.getTimeUntilReset(clientIp)
                    ));
        }

        try {
            ShortenUrlResponse response = urlShortenerService.shortenUrl(request, clientIp);
            return ResponseEntity.ok(response);
        } catch (IllegalArgumentException e) {
            return ResponseEntity.badRequest().body(Map.of("error", e.getMessage()));
        } catch (Exception e) {
            return ResponseEntity.internalServerError().body(Map.of("error", e.getMessage()));
        }
    }

    @GetMapping("/{shortCode}")
    public ResponseEntity<Void> redirectToUrl(@PathVariable String shortCode,
                                              HttpServletRequest httpRequest,
                                              HttpServletResponse httpResponse) {

        String clientIp = getClientIp(httpRequest);
        String userAgent = httpRequest.getHeader("User-Agent");
        String referer = httpRequest.getHeader("Referer");

        Optional<String> originalUrl = urlShortenerService.getOriginalUrl(shortCode);

        if(originalUrl.isPresent()) {
            urlShortenerService.recordClick(shortCode,clientIp, userAgent,referer);
            httpResponse.setHeader("Location", originalUrl.get());
            return ResponseEntity.status(HttpStatus.FOUND).build();
        } else {
            return ResponseEntity.notFound().build();
        }

    }

    @GetMapping("/stats/{shortCode}")
    public ResponseEntity<?> getStats(@PathVariable String shortCode) {
        Optional<UrlStatsResponse> stats = urlShortenerService.getUrlStats(shortCode);
        if(stats.isPresent()) {
            return ResponseEntity.ok(stats.get());
        } else {
            return ResponseEntity.status(HttpStatus.NOT_FOUND)
                    .body(Map.of("error", "short code not found"));
        }
    }

    @GetMapping("/analytics/{shortCode}")
    public ResponseEntity<?> getUrlAnalytics(@PathVariable String shortCode) {
        Optional<UrlAnalyticsResponse> analytics = urlShortenerService.getUrlAnalytics(shortCode);
        if(analytics.isPresent()) {
            return ResponseEntity.ok(analytics.get());
        } else {
            return ResponseEntity.status(HttpStatus.NOT_FOUND)
                    .body(Map.of("error", "short code not found"));
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
