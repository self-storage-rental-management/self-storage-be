package com.storagehub.security;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.storagehub.common.api.ApiErrorWriter;
import com.storagehub.common.api.ErrorCode;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.HandlerInterceptor;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

@Configuration
@RequiredArgsConstructor
public class RequestAbuseLimits implements WebMvcConfigurer {
    private final RequestRateLimiter limiter;
    private final ObjectMapper mapper;

    @Override
    public void addInterceptors(InterceptorRegistry registry) {
        registry.addInterceptor(new HandlerInterceptor() {
            @Override
            public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) throws java.io.IOException {
                if (!"POST".equals(request.getMethod())) return true;
                String path = request.getServletPath();
                boolean reservation = path.equals("/api/customer/reservations");
                if (!reservation && !java.util.Set.of("/api/auth/register", "/api/auth/forgot-password",
                    "/api/auth/verify-email/resend", "/api/auth/verify-email", "/api/auth/reset-password").contains(path)) return true;
                // Use only the server-resolved remote address, never an arbitrary forwarded header.
                if (limiter.allow("ip:" + path, request.getRemoteAddr(), reservation ? 12 : 20, 900)) return true;
                response.setHeader("Retry-After", "900");
                ApiErrorWriter.write(response, mapper, 429, ErrorCode.RATE_LIMITED, "Bạn thao tác quá nhiều lần. Vui lòng thử lại sau ít phút.");
                return false;
            }
        }).addPathPatterns("/api/auth/**", "/api/customer/reservations");
    }
}
