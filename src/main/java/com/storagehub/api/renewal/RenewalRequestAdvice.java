package com.storagehub.api.renewal;

import com.storagehub.common.api.*;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.MissingRequestHeaderException;
import org.springframework.web.bind.annotation.*;

/** Scoped to Renewal only; leave other modules' exception behavior unchanged. */
@RestControllerAdvice(assignableTypes={CustomerRenewalController.class,ManagerRenewalController.class})
@Order(Ordered.HIGHEST_PRECEDENCE)
public class RenewalRequestAdvice {
    @ExceptionHandler(MissingRequestHeaderException.class)
    public ResponseEntity<ApiErrorResponse> missingHeader(MissingRequestHeaderException exception){
        return ResponseEntity.badRequest().body(new ApiErrorResponse(new ApiError(ErrorCode.VALIDATION_ERROR.name(),
            "Required request header is missing: "+exception.getHeaderName(),null,CorrelationIdContext.current())));
    }
}
