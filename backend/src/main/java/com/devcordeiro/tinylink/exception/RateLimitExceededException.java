package com.devcordeiro.tinylink.exception;

import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.web.ErrorResponseException;

public class RateLimitExceededException extends ErrorResponseException {

    public RateLimitExceededException(int remainingRequests, long timeUntilReset) {
        super(HttpStatus.TOO_MANY_REQUESTS, problem(remainingRequests, timeUntilReset), null);
        getHeaders().set(HttpHeaders.RETRY_AFTER, String.valueOf(timeUntilReset));
    }

    private static ProblemDetail problem(int remainingRequests, long timeUntilReset) {
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(
                HttpStatus.TOO_MANY_REQUESTS, "Too many requests, try again in " + timeUntilReset + " seconds");
        problem.setTitle("Rate limit exceeded");
        problem.setProperty("remainingRequests", remainingRequests);
        problem.setProperty("timeUntilReset", timeUntilReset);
        return problem;
    }
}
