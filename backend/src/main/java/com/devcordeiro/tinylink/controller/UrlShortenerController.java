package com.devcordeiro.tinylink.controller;

import com.devcordeiro.tinylink.service.RateLimitService;
import com.devcordeiro.tinylink.service.UrlShortenerService;
import org.springframework.stereotype.Service;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@Service
@RequestMapping("/api")
public class UrlShortenerController {

    private final UrlShortenerService urlShortenerService;
    private final RateLimitService rateLimitService;

    public UrlShortenerController(UrlShortenerService urlShortenerService, RateLimitService rateLimitService) {
        this.urlShortenerService = urlShortenerService;
        this.rateLimitService = rateLimitService;
    }
}
