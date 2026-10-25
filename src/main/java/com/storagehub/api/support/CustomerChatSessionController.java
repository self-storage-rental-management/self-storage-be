package com.storagehub.api.support;

import com.storagehub.common.api.*;
import com.storagehub.security.ActorContext;
import com.storagehub.service.support.CustomerChatSessionService;
import jakarta.validation.Valid;
import jakarta.validation.constraints.*;
import java.util.*;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

@RestController @RequiredArgsConstructor @RequestMapping("/api/customer/chat/session")
public class CustomerChatSessionController {
    public record Message(@Min(0) long id,@NotBlank @Pattern(regexp="assistant|customer") String role,
        @NotBlank @Size(max=4000) String body,@Pattern(regexp="pending|failed") String status,
        Boolean truncated,@Pattern(regexp="FAQ|GEMINI") String source) {}
    public record Snapshot(@NotNull @Size(max=200) List<@NotNull @Valid Message> messages,UUID ticketId,boolean autoReturn) {}
    public record Save(@NotNull @PositiveOrZero Long expectedVersion,@NotNull UUID mutationId,@NotNull @Valid Snapshot snapshot) {}
    public record Session(long version,Snapshot snapshot) {}
    private final ActorContext actors;
    private final CustomerChatSessionService service;
    @GetMapping public ApiResponse<Session> get(){return new ApiResponse<>(service.read(actors.required()),CorrelationIdContext.current());}
    @PutMapping public ApiResponse<Session> save(@Valid @RequestBody Save body){return new ApiResponse<>(service.save(actors.required(),body),CorrelationIdContext.current());}
}
