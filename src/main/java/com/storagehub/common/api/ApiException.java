package com.storagehub.common.api;

import org.springframework.http.HttpStatus;

public class ApiException extends RuntimeException {

    private final ErrorCode code;
    private final HttpStatus status;
    private final Object details;

    public ApiException(ErrorCode code, HttpStatus status, String message) {
        this(code, status, message, null);
    }

    public ApiException(ErrorCode code, HttpStatus status, String message, Object details) {
        super(message);
        this.code = code;
        this.status = status;
        this.details = details;
    }

    public ErrorCode getCode() {
        return code;
    }

    public HttpStatus getStatus() {
        return status;
    }

    public Object getDetails() {
        return details;
    }
}
