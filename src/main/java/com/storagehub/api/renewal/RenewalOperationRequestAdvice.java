package com.storagehub.api.renewal;

import com.storagehub.common.api.*;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.MissingRequestHeaderException;
import org.springframework.web.bind.annotation.*;

@RestControllerAdvice(assignableTypes={CustomerRenewalOperationController.class,StaffRenewalOperationController.class,ManagerRenewalOperationController.class})
@Order(Ordered.HIGHEST_PRECEDENCE)
public class RenewalOperationRequestAdvice {
    @ExceptionHandler(MissingRequestHeaderException.class)
    public ResponseEntity<ApiErrorResponse> missingHeader(MissingRequestHeaderException e){return ResponseEntity.badRequest().body(new ApiErrorResponse(new ApiError(ErrorCode.VALIDATION_ERROR.name(),"Required request header is missing: "+e.getHeaderName(),null,CorrelationIdContext.current())));}
}
