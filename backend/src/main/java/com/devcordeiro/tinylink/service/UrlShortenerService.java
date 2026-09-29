package com.devcordeiro.tinylink.service;

import com.devcordeiro.tinylink.dto.ShortenUrlRequest;
import com.devcordeiro.tinylink.dto.ShortenUrlResponse;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.stereotype.Service;

@Service
@RequiredArgsConstructor
@Slf4j
public class UrlShortenerService {

    private final RedisTemplate<String, Object> redisTemplate;

    public ShortenUrlResponse shortenUrl(ShortenUrlRequest request, String clientIp) {
        return null;
    }
}
