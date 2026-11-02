package com.storagehub.common.api;

import jakarta.validation.ConstraintViolationException;
import java.util.LinkedHashMap;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.core.AuthenticationException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.MissingServletRequestParameterException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;
import org.springframework.web.multipart.MaxUploadSizeExceededException;
import org.springframework.web.multipart.support.MissingServletRequestPartException;
import org.springframework.web.servlet.NoHandlerFoundException;
import org.springframework.web.servlet.resource.NoResourceFoundException;

@RestControllerAdvice
public class GlobalExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(GlobalExceptionHandler.class);

    @ExceptionHandler(ApiException.class)
    public ResponseEntity<ApiErrorResponse> handleApiException(ApiException exception) {
        return error(exception.getStatus(), exception.getCode(), exception.getMessage(), exception.getDetails());
    }

    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<ApiErrorResponse> handleValidation(MethodArgumentNotValidException exception) {
        Map<String, String> details = new LinkedHashMap<>();
        exception.getBindingResult().getFieldErrors()
            .forEach(error -> details.putIfAbsent(error.getField(), error.getDefaultMessage()));
        return error(HttpStatus.BAD_REQUEST, ErrorCode.VALIDATION_ERROR, "Thông tin gửi lên chưa hợp lệ", details);
    }

    @ExceptionHandler(ConstraintViolationException.class)
    public ResponseEntity<ApiErrorResponse> handleConstraintViolation(ConstraintViolationException exception) {
        return error(HttpStatus.BAD_REQUEST, ErrorCode.VALIDATION_ERROR, "Thông tin gửi lên chưa hợp lệ", exception.getMessage());
    }

    @ExceptionHandler(HttpMessageNotReadableException.class)
    public ResponseEntity<ApiErrorResponse> handleUnreadableBody(HttpMessageNotReadableException exception) {
        log.warn("Unreadable request body: {}", exception.getMessage());
        return error(HttpStatus.BAD_REQUEST, ErrorCode.VALIDATION_ERROR,
            "Thông tin gửi lên chưa đúng định dạng. Vui lòng kiểm tra lại các mục đã nhập.", exception.getMessage());
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
    public ResponseEntity<ApiErrorResponse> handleAccessDenied(AccessDeniedException exception) {
        return error(HttpStatus.FORBIDDEN, ErrorCode.FORBIDDEN, "Access is denied", null);
    }

    @ExceptionHandler({ NoResourceFoundException.class, NoHandlerFoundException.class })
    public ResponseEntity<ApiErrorResponse> handleNotFound(Exception exception) {
        return error(HttpStatus.NOT_FOUND, ErrorCode.NOT_FOUND, "The requested endpoint does not exist", null);
    }

    @ExceptionHandler(MaxUploadSizeExceededException.class)
    public ResponseEntity<ApiErrorResponse> handleMaxUploadSizeExceeded() {
        return error(HttpStatus.PAYLOAD_TOO_LARGE, ErrorCode.VALIDATION_ERROR,
            "Tệp đính kèm vượt quá dung lượng tối đa cho phép (tối đa 30MB). Vui lòng chọn tệp nhỏ hơn.", null);
    }

    @ExceptionHandler(org.springframework.web.context.request.async.AsyncRequestNotUsableException.class)
    public void handleDisconnectedResponse(org.springframework.web.context.request.async.AsyncRequestNotUsableException exception) {
        // The response is no longer writable. Do not serialize JSON into a closed SSE stream.
        log.debug("Client disconnected from async response: {}", exception.getMessage());
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
        return ResponseEntity.status(status).body(new ApiErrorResponse(
            new ApiError(code.name(), message, details, CorrelationIdContext.current())
        ));
    }
}
