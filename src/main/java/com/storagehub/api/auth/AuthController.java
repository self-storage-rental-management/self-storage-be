package com.storagehub.api.auth;

import com.storagehub.common.api.ApiResponse;
import com.storagehub.common.api.CorrelationIdContext;
import com.storagehub.service.AuthService;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/auth")
@RequiredArgsConstructor
public class AuthController {

    private final AuthService authService;

    @PostMapping("/register")
    public ApiResponse<ActorResponse> register(@Valid @RequestBody RegisterRequest request) {
        return new ApiResponse<>(authService.register(request), CorrelationIdContext.current());
    }

    @PostMapping("/login")
    public ApiResponse<AuthResponse> login(@Valid @RequestBody LoginRequest request, HttpServletRequest httpRequest) {
        return new ApiResponse<>(
            authService.login(request, httpRequest.getRemoteAddr(), httpRequest.getHeader("User-Agent")),
            CorrelationIdContext.current()
        );
    }

    @PostMapping("/logout")
    public ApiResponse<Map<String, Boolean>> logout() {
        authService.logout();
        return new ApiResponse<>(Map.of("loggedOut", true), CorrelationIdContext.current());
    }

    @GetMapping("/me")
    public ApiResponse<ActorResponse> me() {
        return new ApiResponse<>(authService.currentActor(), CorrelationIdContext.current());
    }
}
