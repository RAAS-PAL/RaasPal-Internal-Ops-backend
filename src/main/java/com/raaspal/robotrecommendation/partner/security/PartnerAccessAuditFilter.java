package com.raaspal.robotrecommendation.partner.security;

import com.raaspal.robotrecommendation.partner.entity.PartnerApiAccessLog;
import com.raaspal.robotrecommendation.partner.repository.PartnerApiAccessLogRepository;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.time.LocalDateTime;

/**
 * Records every partner-API request — path, query, outcome, duration, caller —
 * into {@code partner_api_access_logs}.
 *
 * <p>Sits <strong>outermost</strong> in the partner chain so it observes the final
 * response status, including 401s from the entry point and 429s from
 * {@link PartnerRateLimitFilter}. Failed authentications are recorded with a null
 * partner, which is the point: rejected attempts are what reveal a leaked or
 * probing key.
 *
 * <p>Auditing must never affect the response: the row is written after the chain
 * completes, and any failure to write is swallowed with a warning. The API stays
 * up even if the audit table does not.
 */
@Slf4j
@Component
public class PartnerAccessAuditFilter extends OncePerRequestFilter {

    private final PartnerApiAccessLogRepository accessLogRepository;
    private final boolean enabled;

    public PartnerAccessAuditFilter(
            PartnerApiAccessLogRepository accessLogRepository,
            @Value("${app.partner.audit-enabled:true}") boolean enabled) {
        this.accessLogRepository = accessLogRepository;
        this.enabled = enabled;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request,
                                    HttpServletResponse response,
                                    FilterChain filterChain) throws ServletException, IOException {
        if (!enabled) {
            filterChain.doFilter(request, response);
            return;
        }

        LocalDateTime requestedAt = LocalDateTime.now();
        long startedAt = System.nanoTime();
        try {
            filterChain.doFilter(request, response);
        } finally {
            // Recorded after the chain so the status is final (including 401/429).
            record(request, response, requestedAt, startedAt);
        }
    }

    private void record(HttpServletRequest request,
                        HttpServletResponse response,
                        LocalDateTime requestedAt,
                        long startedAt) {
        try {
            PartnerPrincipal principal = authenticatedPrincipal(request);
            accessLogRepository.save(PartnerApiAccessLog.builder()
                    .partnerId(principal == null ? null : principal.partnerId())
                    .apiKeyId(principal == null ? null : principal.apiKeyId())
                    .method(request.getMethod())
                    .path(truncate(request.getRequestURI(), 500))
                    .queryString(truncate(request.getQueryString(), 1000))
                    .status(response.getStatus())
                    .durationMs((int) ((System.nanoTime() - startedAt) / 1_000_000))
                    .clientIp(truncate(clientIp(request), 64))
                    .requestedAt(requestedAt)
                    .build());
        } catch (Exception e) {
            // Auditing is observability, not correctness — never fail the request for it.
            log.warn("Could not record partner API access for {}: {}", request.getRequestURI(), e.getMessage());
        }
    }

    /**
     * The authenticated caller, or null if the request never authenticated.
     *
     * <p>Read from the request attribute set by {@link ApiKeyAuthFilter}, not the
     * SecurityContext: this filter is outermost, and by the time it regains control
     * Spring Security has already cleared the context — which silently attributed
     * successful requests to no partner. The SecurityContext is still consulted as
     * a fallback.
     */
    private PartnerPrincipal authenticatedPrincipal(HttpServletRequest request) {
        if (request.getAttribute(ApiKeyAuthFilter.PARTNER_PRINCIPAL_ATTRIBUTE)
                instanceof PartnerPrincipal fromRequest) {
            return fromRequest;
        }
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        if (authentication != null && authentication.getPrincipal() instanceof PartnerPrincipal principal) {
            return principal;
        }
        return null;
    }

    /** Prefers the proxy-forwarded address — Render terminates TLS in front of the app. */
    private String clientIp(HttpServletRequest request) {
        String forwarded = request.getHeader("X-Forwarded-For");
        if (StringUtils.hasText(forwarded)) {
            int comma = forwarded.indexOf(',');
            return (comma > 0 ? forwarded.substring(0, comma) : forwarded).trim();
        }
        return request.getRemoteAddr();
    }

    private static String truncate(String value, int max) {
        if (value == null) {
            return null;
        }
        return value.length() <= max ? value : value.substring(0, max);
    }
}
