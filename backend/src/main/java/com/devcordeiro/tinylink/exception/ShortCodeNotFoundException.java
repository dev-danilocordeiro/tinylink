package com.devcordeiro.tinylink.exception;

import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.web.ErrorResponseException;

public class ShortCodeNotFoundException extends ErrorResponseException {

    public ShortCodeNotFoundException(String shortCode) {
        super(HttpStatus.NOT_FOUND, problem(shortCode), null);
    }

    private static ProblemDetail problem(String shortCode) {
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(
                HttpStatus.NOT_FOUND, "Short code '" + shortCode + "' not found");
        problem.setTitle("Short code not found");
        problem.setProperty("shortCode", shortCode);
        return problem;
    }
}
