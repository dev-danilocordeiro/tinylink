package com.devcordeiro.tinylink.controller;

import com.devcordeiro.tinylink.dto.ShortenUrlRequest;
import com.devcordeiro.tinylink.dto.ShortenUrlResponse;
import com.devcordeiro.tinylink.exception.AliasAlreadyExistsException;
import com.devcordeiro.tinylink.service.RateLimitService;
import com.devcordeiro.tinylink.service.UrlShortenerService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.assertj.MockMvcTester;

import java.time.Instant;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@WebMvcTest(UrlShortenerController.class)
class UrlShortenerControllerTest {

    private static final MediaType PROBLEM_JSON = MediaType.APPLICATION_PROBLEM_JSON;

    @Autowired
    private MockMvcTester mvc;

    @MockitoBean
    private UrlShortenerService urlShortenerService;

    @MockitoBean
    private RateLimitService rateLimitService;

    @BeforeEach
    void allowRequests() {
        when(rateLimitService.tryAcquire(anyString())).thenReturn(new RateLimitService.Decision(true, 1, 0));
    }

    @Test
    void unknownShortCodeOnStatsReturnsNotFoundProblem() {
        when(urlShortenerService.getUrlStats("nope")).thenReturn(Optional.empty());

        assertThat(mvc.get().uri("/api/stats/nope"))
                .hasStatus(HttpStatus.NOT_FOUND)
                .hasContentTypeCompatibleWith(PROBLEM_JSON)
                .bodyJson()
                .satisfies(json -> {
                    assertThat(json).extractingPath("$.title").isEqualTo("Short code not found");
                    assertThat(json).extractingPath("$.detail").isEqualTo("Short code 'nope' not found");
                    assertThat(json).extractingPath("$.shortCode").isEqualTo("nope");
                    assertThat(json).extractingPath("$.instance").isEqualTo("/api/stats/nope");
                });
    }

    @Test
    void unknownShortCodeOnRedirectReturnsNotFoundProblemWithoutRecordingAClick() {
        when(urlShortenerService.getOriginalUrl("nope")).thenReturn(Optional.empty());

        assertThat(mvc.get().uri("/api/nope"))
                .hasStatus(HttpStatus.NOT_FOUND)
                .hasContentTypeCompatibleWith(PROBLEM_JSON);
        verify(urlShortenerService, never()).recordClick(anyString(), any(), any(), any());
    }

    @Test
    void knownShortCodeRedirectsToTheOriginalUrl() {
        when(urlShortenerService.getOriginalUrl("abc123")).thenReturn(Optional.of("https://example.com"));

        assertThat(mvc.get().uri("/api/abc123"))
                .hasStatus(HttpStatus.FOUND)
                .hasHeader(HttpHeaders.LOCATION, "https://example.com");
    }

    @Test
    void duplicateAliasReturnsConflictProblem() {
        when(urlShortenerService.shortenUrl(any(), anyString())).thenThrow(new AliasAlreadyExistsException("taken"));

        assertThat(mvc.post().uri("/api/shorten")
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                        {"originalUrl": "https://example.com", "customAlias": "taken"}
                        """))
                .hasStatus(HttpStatus.CONFLICT)
                .hasContentTypeCompatibleWith(PROBLEM_JSON)
                .bodyJson()
                .satisfies(json -> {
                    assertThat(json).extractingPath("$.title").isEqualTo("Alias already exists");
                    assertThat(json).extractingPath("$.alias").isEqualTo("taken");
                });
    }

    @Test
    void invalidAliasReturnsBadRequestProblemListingTheField() {
        assertThat(mvc.post().uri("/api/shorten")
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                        {"originalUrl": "https://example.com", "customAlias": "a/b"}
                        """))
                .hasStatus(HttpStatus.BAD_REQUEST)
                .hasContentTypeCompatibleWith(PROBLEM_JSON)
                .bodyJson()
                .satisfies(json -> {
                    assertThat(json).extractingPath("$.title").isEqualTo("Invalid request");
                    assertThat(json).extractingPath("$.errors[0].field").isEqualTo("customAlias");
                });
    }

    @Test
    void blankAliasIsAcceptedSoACodeGetsGenerated() {
        when(urlShortenerService.shortenUrl(any(), anyString())).thenReturn(
                ShortenUrlResponse.builder().shortCode("abc123").build());

        assertThat(mvc.post().uri("/api/shorten")
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                        {"originalUrl": "https://example.com", "customAlias": ""}
                        """))
                .hasStatus(HttpStatus.OK);
    }

    @Test
    void expiresAtWithAnOffsetIsConvertedToTheSameInstant() {
        when(urlShortenerService.shortenUrl(any(), anyString())).thenReturn(
                ShortenUrlResponse.builder().shortCode("abc123").build());

        assertThat(mvc.post().uri("/api/shorten")
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                        {"originalUrl": "https://example.com", "expiresAt": "2030-01-01T10:00:00-03:00"}
                        """))
                .hasStatus(HttpStatus.OK);

        ArgumentCaptor<ShortenUrlRequest> request = ArgumentCaptor.forClass(ShortenUrlRequest.class);
        verify(urlShortenerService).shortenUrl(request.capture(), anyString());
        assertThat(request.getValue().getExpiresAt()).isEqualTo(Instant.parse("2030-01-01T13:00:00Z"));
    }

    @Test
    void expiresAtWithoutAnOffsetIsRejected() {
        assertThat(mvc.post().uri("/api/shorten")
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                        {"originalUrl": "https://example.com", "expiresAt": "2030-01-01T10:00:00"}
                        """))
                .hasStatus(HttpStatus.BAD_REQUEST)
                .hasContentTypeCompatibleWith(PROBLEM_JSON)
                .bodyJson()
                .satisfies(json -> {
                    assertThat(json).extractingPath("$.title").isEqualTo("Invalid request");
                    assertThat(json).extractingPath("$.errors[0].field").isEqualTo("expiresAt");
                    assertThat(json).extractingPath("$.errors[0].message").asString().contains("with an offset");
                });
    }

    @Test
    void malformedJsonStillReturnsBadRequestProblem() {
        assertThat(mvc.post().uri("/api/shorten")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{not json"))
                .hasStatus(HttpStatus.BAD_REQUEST)
                .hasContentTypeCompatibleWith(PROBLEM_JSON);
    }

    @Test
    void exceededRateLimitReturnsTooManyRequestsProblemWithRetryAfter() {
        when(rateLimitService.tryAcquire(anyString())).thenReturn(new RateLimitService.Decision(false, 0, 42));

        assertThat(mvc.post().uri("/api/shorten")
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                        {"originalUrl": "https://example.com"}
                        """))
                .hasStatus(HttpStatus.TOO_MANY_REQUESTS)
                .hasHeader(HttpHeaders.RETRY_AFTER, "42")
                .bodyJson()
                .satisfies(json -> {
                    assertThat(json).extractingPath("$.title").isEqualTo("Rate limit exceeded");
                    assertThat(json).extractingPath("$.remainingRequests").isEqualTo(0);
                    assertThat(json).extractingPath("$.timeUntilReset").isEqualTo(42);
                });
    }

    @Test
    void deletingAnActiveLinkReturnsNoContent() {
        when(urlShortenerService.deleteUrl("abc123")).thenReturn(true);

        assertThat(mvc.delete().uri("/api/abc123")).hasStatus(HttpStatus.NO_CONTENT);
    }

    @Test
    void deletingAnUnknownOrAlreadyDeletedLinkReturnsNotFoundProblem() {
        when(urlShortenerService.deleteUrl("abc123")).thenReturn(false);

        assertThat(mvc.delete().uri("/api/abc123"))
                .hasStatus(HttpStatus.NOT_FOUND)
                .hasContentTypeCompatibleWith(PROBLEM_JSON);
    }

    @Test
    void unexpectedErrorReturnsGenericProblemWithoutLeakingTheMessage() {
        when(urlShortenerService.shortenUrl(any(), anyString())).thenThrow(new IllegalStateException("redis password is hunter2"));

        assertThat(mvc.post().uri("/api/shorten")
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                        {"originalUrl": "https://example.com"}
                        """))
                .hasStatus(HttpStatus.INTERNAL_SERVER_ERROR)
                .hasContentTypeCompatibleWith(PROBLEM_JSON)
                .bodyJson()
                .satisfies(json -> {
                    assertThat(json).extractingPath("$.detail").isEqualTo("An unexpected error occurred");
                    assertThat(json.getJson()).doesNotContain("hunter2");
                });
    }
}
