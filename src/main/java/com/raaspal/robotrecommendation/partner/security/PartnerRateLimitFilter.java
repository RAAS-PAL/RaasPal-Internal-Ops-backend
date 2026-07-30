package com.raaspal.robotrecommendation.partner.security;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.time.LocalDateTime;
import java.time.temporal.ChronoUnit;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Throttles the partner API per API key so one partner cannot exhaust the
 * service for everyone else — a partner looping over dozens of robots is normal,
 * a partner polling in a tight loop is not.
 *
 * <p>Implemented as a <strong>fixed window</strong> counter (requests per minute)
 * in an in-memory Caffeine cache, which is deliberate: it needs no Redis or other
 * shared store, and Caffeine is already a dependency. The trade-off is that the
 * budget is <em>per application instance</em> — with more than one instance the
 * effective limit multiplies. That is acceptable for a small number of partners
 * pulling periodically, and can be swapped for a shared counter later without
 * touching callers.
 *
 * <p>Runs <em>after</em> authentication so the data-API budget is tied to the
 * credential rather than an IP. There are two budgets: data requests are metered
 * per authenticated partner, and the {@code permitAll} token endpoint is metered
 * per client IP, since a caller has no identity until the exchange succeeds. Any
 * other unauthenticated request passes straight through to be rejected as 401 by
 * the security chain.
 */
@Slf4j
@Component
public class PartnerRateLimitFilter extends OncePerRequestFilter {

    /** Kept in step with the {@code permitAll} matcher in {@code PartnerSecurityConfig}. */
    static final String TOKEN_ENDPOINT = "/api/partner/v1/oauth/token";

    private final ObjectMapper objectMapper = new ObjectMapper();
    private final boolean enabled;
    private final int requestsPerMinute;
    private final int tokenRequestsPerMinute;

    /** Key: "<partnerId|ip>|<minute>". Entries outlive their window briefly, then expire. */
    private final Cache<String, AtomicInteger> counters = Caffeine.newBuilder()
            .expireAfterWrite(2, TimeUnit.MINUTES)
            .maximumSize(10_000)
            .build();

    public PartnerRateLimitFilter(
            @Value("${app.partner.rate-limit-enabled:true}") boolean enabled,
            @Value("${app.partner.rate-limit-per-minute:120}") int requestsPerMinute,
            @Value("${app.partner.token-rate-limit-per-minute:20}") int tokenRequestsPerMinute) {
        this.enabled = enabled;
        this.requestsPerMinute = requestsPerMinute;
        this.tokenRequestsPerMinute = tokenRequestsPerMinute;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request,
                                    HttpServletResponse response,
                                    FilterChain filterChain) throws ServletException, IOException {
        if (!enabled) {
            filterChain.doFilter(request, response);
            return;
        }

        if (isTokenRequest(request)) {
            meter(request, response, filterChain,
                    "ip:" + ClientIpResolver.resolve(request), tokenRequestsPerMinute);
            return;
        }

        UUID partnerId = authenticatedPartnerId();
        if (partnerId == null) {
            // Nothing to meter against; the security chain will answer 401.
            filterChain.doFilter(request, response);
            return;
        }
        meter(request, response, filterChain, partnerId.toString(), requestsPerMinute);
    }

    /**
     * The token endpoint is {@code permitAll}, so nothing else stands between the
     * open internet and a database lookup plus an audit insert per request. Metering
     * it per <em>IP</em> rather than per credential is the only option available —
     * before the exchange succeeds there is no credential to attribute the request
     * to.
     *
     * <p>This is not about guessing secrets: a client secret carries 256 bits of
     * entropy and is not reachable by brute force at any rate. It is about the two
     * database writes an anonymous caller can trigger per request, and an audit table
     * an unauthenticated loop could otherwise fill unbounded. A legitimate client
     * caches its token for the full hour and needs one exchange per hour, so a
     * generous per-minute ceiling costs real callers nothing.
     */
    private boolean isTokenRequest(HttpServletRequest request) {
        return TOKEN_ENDPOINT.equals(request.getRequestURI());
    }

    private void meter(HttpServletRequest request,
                       HttpServletResponse response,
                       FilterChain filterChain,
                       String subject,
                       int limit) throws ServletException, IOException {
        String window = subject + "|" + LocalDateTime.now().truncatedTo(ChronoUnit.MINUTES);
        int used = counters.get(window, k -> new AtomicInteger()).incrementAndGet();

        if (used > limit) {
            log.warn("Rate limit exceeded for {} ({} requests this minute, limit {})",
                    subject, used, limit);
            writeTooManyRequests(response, limit);
            return;
        }

        // Let a well-behaved client pace itself instead of discovering the limit by hitting it.
        response.setHeader("X-RateLimit-Limit", String.valueOf(limit));
        response.setHeader("X-RateLimit-Remaining", String.valueOf(Math.max(limit - used, 0)));
        filterChain.doFilter(request, response);
    }

    /**
     * The partner to meter, or null when the request is not an authenticated partner
     * request — in which case this filter does nothing and passes straight through.
     * Metering is per credential rather than per IP, so an unauthenticated request
     * has nothing to meter against.
     */
    private UUID authenticatedPartnerId() {
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        if (authentication != null && authentication.getPrincipal() instanceof PartnerPrincipal principal) {
            return principal.partnerId();
        }
        return null;
    }

    private void writeTooManyRequests(HttpServletResponse response, int limit) throws IOException {
        response.setStatus(HttpStatus.TOO_MANY_REQUESTS.value());
        response.setContentType(MediaType.APPLICATION_JSON_VALUE);
        // Seconds until the current fixed window rolls over.
        response.setHeader("Retry-After", String.valueOf(60 - LocalDateTime.now().getSecond()));
        objectMapper.writeValue(response.getOutputStream(), Map.of(
                "success", false,
                "message", "Rate limit exceeded: at most " + limit
                        + " requests per minute. Please slow down and retry.",
                "timestamp", LocalDateTime.now().toString()));
    }
}
