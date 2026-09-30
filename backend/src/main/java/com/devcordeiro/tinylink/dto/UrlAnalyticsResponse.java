package com.devcordeiro.tinylink.dto;

import com.devcordeiro.tinylink.model.ClickEvent;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.Instant;
import java.util.List;
import java.util.Map;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class UrlAnalyticsResponse {
    private String shortCode;
    private String originalUrl;
    private int totalClicks;
    private Instant createdAt;
    private Instant expiresAt;
    private List<ClickEvent> recentClicks;
    private Map<String, Integer> clicksByReferer;
    private Map<String, Integer> clicksByHour;
    private Map<String, Integer> clicksByDay;
}
