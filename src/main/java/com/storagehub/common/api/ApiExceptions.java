package com.storagehub.common.api;

import org.springframework.http.HttpStatus;

public final class ApiExceptions {

    private ApiExceptions() {
    }

    public static ApiException validation(String message, Object details) {
        return new ApiException(ErrorCode.VALIDATION_ERROR, HttpStatus.BAD_REQUEST, message, details);
    }

    public static ApiException unauthorized(String message) {
        return new ApiException(ErrorCode.UNAUTHORIZED, HttpStatus.UNAUTHORIZED, message);
    }

    public static ApiException forbidden(String message) {
        return new ApiException(ErrorCode.FORBIDDEN, HttpStatus.FORBIDDEN, message);
    }

    public static ApiException notFound(String message) {
        return new ApiException(ErrorCode.NOT_FOUND, HttpStatus.NOT_FOUND, message);
    }

    public static ApiException conflict(String message) {
        return new ApiException(ErrorCode.CONFLICT, HttpStatus.CONFLICT, message);
    }
}
