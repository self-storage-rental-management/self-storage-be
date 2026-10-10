package com.storagehub.api.support;

import com.fasterxml.jackson.databind.*;
import com.storagehub.common.api.*;
import java.io.*;
import java.lang.reflect.Type;
import java.util.*;
import org.springframework.core.*;
import org.springframework.core.annotation.Order;
import org.springframework.http.*;
import org.springframework.http.converter.HttpMessageConverter;
import org.springframework.web.bind.MissingRequestHeaderException;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.servlet.mvc.method.annotation.RequestBodyAdviceAdapter;

/** Scoped strict request handling; does not change Jackson behavior for other modules. */
@RestControllerAdvice(assignableTypes={CustomerSupportController.class,ManagerSupportController.class,StaffSupportController.class})
@Order(Ordered.HIGHEST_PRECEDENCE)
public class SupportRequestAdvice extends RequestBodyAdviceAdapter {
    private final ObjectMapper mapper;
    public SupportRequestAdvice(ObjectMapper mapper){this.mapper=mapper;}
    @Override public boolean supports(MethodParameter parameter,Type type,Class<? extends HttpMessageConverter<?>> converter){return parameter.getParameterType().getEnclosingClass()==SupportCommands.class;}
    @Override public HttpInputMessage beforeBodyRead(HttpInputMessage input,MethodParameter parameter,Type type,Class<? extends HttpMessageConverter<?>> converter) throws IOException {
        byte[] bytes=input.getBody().readNBytes(65_537);if(bytes.length>65_536)throw ApiExceptions.validation("Support request body exceeds 64 KiB",null);
        JsonNode root;
        try(var parser=mapper.getFactory().createParser(bytes)){
            parser.enable(com.fasterxml.jackson.core.JsonParser.Feature.STRICT_DUPLICATE_DETECTION);
            root=mapper.readTree(parser);
            if(parser.nextToken()!=null)throw new IOException("Trailing Support JSON");
        }catch(Exception e){throw ApiExceptions.validation("Invalid Support JSON",null);}
        check(root,parameter.getParameterType());
        if(root.hasNonNull("linkedRecord"))check(root.get("linkedRecord"),SupportCommands.Link.class);
        return new HttpInputMessage(){public InputStream getBody(){return new ByteArrayInputStream(bytes);}public HttpHeaders getHeaders(){return input.getHeaders();}};
    }
    private void check(JsonNode node,Class<?> type){if(node==null||!node.isObject())throw ApiExceptions.validation("Support body must be an object",null);var names=new HashSet<String>();for(var field:type.getRecordComponents())names.add(field.getName());node.fieldNames().forEachRemaining(name->{if(!names.contains(name))throw ApiExceptions.validation("Unsupported Support field: "+name,null);});}
    @ExceptionHandler(MissingRequestHeaderException.class)
    public ResponseEntity<ApiErrorResponse> missingHeader(MissingRequestHeaderException e){return ResponseEntity.badRequest().body(new ApiErrorResponse(new ApiError(ErrorCode.VALIDATION_ERROR.name(),"Required request header is missing: "+e.getHeaderName(),null,CorrelationIdContext.current())));}
}
