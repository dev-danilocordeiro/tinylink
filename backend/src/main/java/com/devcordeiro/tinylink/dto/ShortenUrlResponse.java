package com.devcordeiro.tinylink.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.Instant;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ShortenUrlResponse {
    private String shortUrl;
    private String shortCode;
    private String originalUrl;
    private Instant createdAt;
    private Instant expiresAt;
}
