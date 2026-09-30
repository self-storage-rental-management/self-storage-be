package com.storagehub.api.auth;

import com.storagehub.common.api.ApiResponse;
import com.storagehub.common.api.CorrelationIdContext;
import com.storagehub.service.AuthChallengeService;
import com.storagehub.service.AuthService;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/auth")
@RequiredArgsConstructor
public class AuthController {

    private final AuthService authService;
    private final AuthChallengeService authChallengeService;

    @PostMapping("/register")
    public ApiResponse<RegisterResponse> register(@Valid @RequestBody RegisterRequest request) {
        return new ApiResponse<>(authService.register(request), CorrelationIdContext.current());
    }

    @PostMapping("/login")
    public ApiResponse<AuthResponse> login(@Valid @RequestBody LoginRequest request, HttpServletRequest httpRequest) {
        return new ApiResponse<>(
            authService.login(request, httpRequest.getRemoteAddr(), httpRequest.getHeader("User-Agent")),
            CorrelationIdContext.current()
        );
    }

    @PostMapping("/refresh")
    public ApiResponse<AuthResponse> refresh(@Valid @RequestBody RefreshTokenRequest request) {
        return new ApiResponse<>(authService.refresh(request), CorrelationIdContext.current());
    }

    @PostMapping("/verify-email")
    public ApiResponse<ActorResponse> verifyEmail(@Valid @RequestBody VerifyEmailRequest request) {
        return new ApiResponse<>(authChallengeService.verifyEmail(request), CorrelationIdContext.current());
    }

    @PostMapping("/forgot-password")
    public ApiResponse<AuthChallengeResponse> forgotPassword(@Valid @RequestBody ForgotPasswordRequest request) {
        return new ApiResponse<>(authChallengeService.requestPasswordReset(request), CorrelationIdContext.current());
    }

    @PostMapping("/reset-password")
    public ApiResponse<PasswordResetResponse> resetPassword(@Valid @RequestBody ResetPasswordRequest request) {
        return new ApiResponse<>(authChallengeService.resetPassword(request), CorrelationIdContext.current());
    }

    @PostMapping("/logout")
    public ApiResponse<Map<String, Boolean>> logout() {
        authService.logout();
        return new ApiResponse<>(Map.of("loggedOut", true), CorrelationIdContext.current());
    }

    @PostMapping("/password")
    public ApiResponse<AuthResponse> changePassword(@Valid @RequestBody ChangePasswordRequest request) {
        return new ApiResponse<>(authService.changePassword(request), CorrelationIdContext.current());
    }

    @GetMapping("/me")
    public ApiResponse<ActorResponse> me() {
        return new ApiResponse<>(authService.currentActor(), CorrelationIdContext.current());
    }

    @PutMapping("/me")
    public ApiResponse<ActorResponse> updateMe(@Valid @RequestBody UpdateProfileRequest request) {
        return new ApiResponse<>(authService.updateCurrentActor(request), CorrelationIdContext.current());
    }
}
