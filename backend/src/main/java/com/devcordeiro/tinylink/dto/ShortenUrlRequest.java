package com.devcordeiro.tinylink.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.Instant;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ShortenUrlRequest {
    @NotBlank(message = "Original URL is required")
    @Pattern(regexp = "^https?://.*", message = "URL must start with http or https")
    private String originalUrl;
    // Empty means "generate one", so the frontend can send the field blank.
    @Pattern(regexp = "^$|^[a-zA-Z0-9_-]{3,30}$",
            message = "Custom alias must be 3 to 30 characters: letters, digits, '-' or '_'")
    private String customAlias;
    private Instant expiresAt;
}
