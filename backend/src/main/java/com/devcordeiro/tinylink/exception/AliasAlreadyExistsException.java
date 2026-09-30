package com.devcordeiro.tinylink.exception;

import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.web.ErrorResponseException;

public class AliasAlreadyExistsException extends ErrorResponseException {

    public AliasAlreadyExistsException(String alias) {
        super(HttpStatus.CONFLICT, problem(alias), null);
    }

    private static ProblemDetail problem(String alias) {
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(
                HttpStatus.CONFLICT, "Custom alias '" + alias + "' already exists");
        problem.setTitle("Alias already exists");
        problem.setProperty("alias", alias);
        return problem;
    }
}
