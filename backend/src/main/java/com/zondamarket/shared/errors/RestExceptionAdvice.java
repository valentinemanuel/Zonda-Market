package com.zondamarket.shared.errors;

import com.zondamarket.shared.errors.ProblemResponse.FieldError;
import com.zondamarket.shared.web.CorrelationIdFilter;
import java.util.List;
import org.slf4j.MDC;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.MediaType;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.context.request.WebRequest;
import org.springframework.web.multipart.MaxUploadSizeExceededException;
import org.springframework.web.method.annotation.HandlerMethodValidationException;
import org.springframework.web.servlet.mvc.method.annotation.ResponseEntityExceptionHandler;

@RestControllerAdvice
public class RestExceptionAdvice extends ResponseEntityExceptionHandler {
    @ExceptionHandler(Exception.class)
    protected ResponseEntity<Object> handleUnexpectedError(Exception exception, WebRequest request) {
        logger.error("Error al procesar una solicitud", exception);
        return createResponseEntity(ProblemDetail.forStatus(HttpStatus.INTERNAL_SERVER_ERROR),
                HttpHeaders.EMPTY, HttpStatus.INTERNAL_SERVER_ERROR, request);
    }

    @Override
    protected ResponseEntity<Object> handleHandlerMethodValidationException(
            HandlerMethodValidationException exception, HttpHeaders headers,
            HttpStatusCode status, WebRequest request) {
        if (!status.is4xxClientError()) {
            return super.handleHandlerMethodValidationException(exception, headers, status, request);
        }
        List<FieldError> errors = exception.getParameterValidationResults().stream()
                .flatMap(result -> result.getResolvableErrors().stream()
                        .map(error -> new FieldError(result.getMethodParameter().getParameterName(), error.getDefaultMessage())))
                .toList();
        return invalidRequest(headers, status, request, errors);
    }

    @Override
    protected ResponseEntity<Object> handleMethodArgumentNotValid(
            MethodArgumentNotValidException exception, HttpHeaders headers,
            HttpStatusCode status, WebRequest request) {
        List<FieldError> errors = exception.getBindingResult().getFieldErrors().stream()
                .map(error -> new FieldError(error.getField(), error.getDefaultMessage()))
                .toList();
        return invalidRequest(headers, status, request, errors);
    }

    @Override
    protected ResponseEntity<Object> handleMaxUploadSizeExceededException(
            MaxUploadSizeExceededException exception, HttpHeaders headers, HttpStatusCode status, WebRequest request) {
        var problem = ProblemDetail.forStatusAndDetail(status, "La imagen admite hasta 5 MiB.");
        problem.setProperty("code", "IMAGE_TOO_LARGE");
        return createResponseEntity(problem, headers, status, request);
    }

    private ResponseEntity<Object> invalidRequest(
            HttpHeaders headers, HttpStatusCode status, WebRequest request, List<FieldError> errors) {
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(status, "Revisá los datos de la solicitud.");
        problem.setProperty("errors", errors);
        return createResponseEntity(problem, headers, status, request);
    }

    @Override
    protected ResponseEntity<Object> createResponseEntity(
            Object body, HttpHeaders headers, HttpStatusCode status, WebRequest request) {
        if (!(body instanceof ProblemDetail problem)) {
            return super.createResponseEntity(body, headers, status, request);
        }
        if (status.is5xxServerError()) {
            problem.setDetail("El servicio no pudo completar la solicitud.");
        }
        if (problem.getProperties() == null || !problem.getProperties().containsKey("code")) {
            String code = switch (status.value()) {
                case 400 -> "INVALID_REQUEST";
                case 404 -> "RESOURCE_NOT_FOUND";
                default -> "HTTP_" + status.value();
            };
            problem.setProperty("code", code);
        }
        if (!problem.getProperties().containsKey("errors")) {
            problem.setProperty("errors", List.of());
        }
        problem.setProperty("traceId", MDC.get(CorrelationIdFilter.TRACE_KEY));
        HttpHeaders responseHeaders = new HttpHeaders();
        responseHeaders.putAll(headers);
        responseHeaders.setContentType(MediaType.APPLICATION_PROBLEM_JSON);
        if (status.equals(HttpStatus.NOT_FOUND) || status.is5xxServerError()) {
            responseHeaders.setCacheControl("no-store");
        }
        return super.createResponseEntity(problem, responseHeaders, status, request);
    }
}
