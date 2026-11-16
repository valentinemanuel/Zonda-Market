package com.zondamarket.shared.errors;

import java.util.List;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.web.ErrorResponseException;

public class ApiProblemException extends ErrorResponseException {
    public ApiProblemException(HttpStatus status, String code, String detail) {
        super(status, createProblem(status, code, detail), null);
    }

    private static ProblemDetail createProblem(HttpStatus status, String code, String detail) {
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(status, detail);
        problem.setProperty("code", code);
        problem.setProperty("errors", List.of());
        return problem;
    }
}
