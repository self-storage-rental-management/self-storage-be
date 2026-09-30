package com.storagehub.common.api;

import jakarta.validation.ConstraintViolationException;
import java.util.LinkedHashMap;
import java.util.Map;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.core.AuthenticationException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;
import org.springframework.web.bind.MissingServletRequestParameterException;
import org.springframework.web.multipart.support.MissingServletRequestPartException;

@Slf4j
@RestControllerAdvice
public class GlobalExceptionHandler {

    @ExceptionHandler(ApiException.class)
    public ResponseEntity<ApiErrorResponse> handleApiException(ApiException exception) {
        return error(exception.getStatus(), exception.getCode(), exception.getMessage(), exception.getDetails());
    }

    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<ApiErrorResponse> handleValidation(MethodArgumentNotValidException exception) {
        Map<String, String> details = new LinkedHashMap<>();
        exception.getBindingResult().getFieldErrors()
            .forEach(error -> details.putIfAbsent(error.getField(), error.getDefaultMessage()));
        return error(HttpStatus.BAD_REQUEST, ErrorCode.VALIDATION_ERROR, "Request validation failed", details);
    }

    @ExceptionHandler(ConstraintViolationException.class)
    public ResponseEntity<ApiErrorResponse> handleConstraintViolation(ConstraintViolationException exception) {
        return error(HttpStatus.BAD_REQUEST, ErrorCode.VALIDATION_ERROR, "Request validation failed", exception.getMessage());
    }

    @ExceptionHandler(HttpMessageNotReadableException.class)
    public ResponseEntity<ApiErrorResponse> handleUnreadableBody() {
        return error(HttpStatus.BAD_REQUEST, ErrorCode.VALIDATION_ERROR, "Request body is malformed", null);
    }

    @ExceptionHandler({
        MethodArgumentTypeMismatchException.class,
        MissingServletRequestParameterException.class,
        MissingServletRequestPartException.class
    })
    public ResponseEntity<ApiErrorResponse> handleRequestShape(Exception exception) {
        return error(HttpStatus.BAD_REQUEST, ErrorCode.VALIDATION_ERROR, "Request parameters are invalid or incomplete", null);
    }

    @ExceptionHandler(DataIntegrityViolationException.class)
    public ResponseEntity<ApiErrorResponse> handleDataConflict() {
        return error(HttpStatus.CONFLICT, ErrorCode.CONFLICT, "The request conflicts with existing data", null);
    }

    @ExceptionHandler(AuthenticationException.class)
    public ResponseEntity<ApiErrorResponse> handleAuthentication() {
        return error(HttpStatus.UNAUTHORIZED, ErrorCode.UNAUTHORIZED, "Authentication is required", null);
    }

    @ExceptionHandler(AccessDeniedException.class)
    public ResponseEntity<ApiErrorResponse> handleAccessDenied() {
        return error(HttpStatus.FORBIDDEN, ErrorCode.FORBIDDEN, "You do not have permission for this resource", null);
    }

    @ExceptionHandler(Exception.class)
    public ResponseEntity<ApiErrorResponse> handleUnexpected(Exception exception) {
        log.error("Unhandled API error, correlationId={}", CorrelationIdContext.current(), exception);
        return error(HttpStatus.INTERNAL_SERVER_ERROR, ErrorCode.INTERNAL_ERROR, "An unexpected error occurred", null);
    }

    private ResponseEntity<ApiErrorResponse> error(
        HttpStatus status,
        ErrorCode code,
        String message,
        Object details
    ) {
        return ResponseEntity.status(status).body(
            new ApiErrorResponse(new ApiError(code.name(), message, details, CorrelationIdContext.current()))
        );
    }
}
