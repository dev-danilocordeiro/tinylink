package com.devcordeiro.tinylink.model;

import lombok.AccessLevel;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.Instant;
import java.util.Queue;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.atomic.AtomicInteger;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class UrlData {
    private String originalUrl;
    private String shortCode;
    private Instant createdAt;
    private Instant expiresAt;
    private String createdBy;
    // Flipped by delete and the cleanup job while redirects read it on other threads.
    private volatile boolean active;

    // Redirects record clicks concurrently, so both are thread-safe and only change through recordClick.
    @Setter(AccessLevel.NONE)
    @Builder.Default
    private AtomicInteger clickCount = new AtomicInteger();

    @Setter(AccessLevel.NONE)
    @Builder.Default
    private Queue<ClickEvent> clickEvents = new ConcurrentLinkedQueue<>();

    public int getClickCount() {
        return clickCount.get();
    }

    public void recordClick(ClickEvent clickEvent) {
        clickEvents.add(clickEvent);
        clickCount.incrementAndGet();
    }
}
