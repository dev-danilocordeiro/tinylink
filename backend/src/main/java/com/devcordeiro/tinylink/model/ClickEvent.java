package com.devcordeiro.tinylink.model;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.Instant;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ClickEvent {
    private Instant timestamp;
    private String ipAddress;
    private String userAgent;
    private String referer;
}
