package com.raaspal.robotrecommendation.knowledge;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.HandlerInterceptor;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

/** Off by default; runs before request-body parsing as well as at the service boundary. */
@Component
public class KcAccountsGate implements HandlerInterceptor, WebMvcConfigurer {
    private final boolean enabled;

    public KcAccountsGate(@Value("${app.kc.accounts.enabled:false}") boolean enabled) {
        this.enabled = enabled;
    }

    public void requireEnabled() {
        if (!enabled) throw new KcAuthException("unavailable", 503, 0);
    }

    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) {
        if ("POST".equals(request.getMethod())) requireEnabled();
        return true;
    }

    @Override
    public void addInterceptors(InterceptorRegistry registry) {
        registry.addInterceptor(this).addPathPatterns(
                "/api/v1/kc/auth/send-code", "/api/v1/kc/auth/verify-code",
                "/api/v1/kc/auth/signup", "/api/v1/kc/auth/reset");
    }
}
