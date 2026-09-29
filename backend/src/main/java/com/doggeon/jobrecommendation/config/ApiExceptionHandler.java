package com.doggeon.jobrecommendation.config;

import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.validation.FieldError;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.context.request.WebRequest;
import org.springframework.web.servlet.mvc.method.annotation.ResponseEntityExceptionHandler;

/**
 * Returns RFC 9457 problem details whose {@code detail} the frontend can show as is.
 * ResponseStatusException reasons pass through the base handler unchanged.
 */
@RestControllerAdvice
public class ApiExceptionHandler extends ResponseEntityExceptionHandler {

    @Override
    protected ResponseEntity<Object> handleMethodArgumentNotValid(MethodArgumentNotValidException ex,
                                                                  HttpHeaders headers, HttpStatusCode status,
                                                                  WebRequest request) {
        FieldError error = ex.getBindingResult().getFieldError();
        String detail = error == null
                ? "입력값을 확인해 주세요."
                : "입력값을 확인해 주세요: " + error.getField() + " - " + error.getDefaultMessage();
        ex.getBody().setDetail(detail);
        return handleExceptionInternal(ex, ex.getBody(), headers, status, request);
    }

    @Override
    protected ResponseEntity<Object> handleHttpMessageNotReadable(HttpMessageNotReadableException ex,
                                                                  HttpHeaders headers, HttpStatusCode status,
                                                                  WebRequest request) {
        var body = createProblemDetail(ex, status, "요청 형식이 올바르지 않습니다. 날짜와 목록 형식을 확인해 주세요.",
                null, null, request);
        return handleExceptionInternal(ex, body, headers, status, request);
    }
}
