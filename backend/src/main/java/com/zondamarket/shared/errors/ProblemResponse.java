package com.zondamarket.shared.errors;

import java.net.URI;
import java.util.List;

public record ProblemResponse(
        URI type, String title, int status, String detail, URI instance, String code, String traceId,
        List<FieldError> errors
) {
    public record FieldError(String field, String message) {
    }
}
