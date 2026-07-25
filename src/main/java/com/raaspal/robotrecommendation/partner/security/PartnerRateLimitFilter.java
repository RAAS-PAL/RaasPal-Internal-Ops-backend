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
 * <p>Runs <em>after</em> authentication so the budget is tied to the key rather
 * than an IP. Unauthenticated requests pass straight through to be rejected as
 * 401 by the security chain.
 */
@Slf4j
@Component
public class PartnerRateLimitFilter extends OncePerRequestFilter {

    private final ObjectMapper objectMapper = new ObjectMapper();
    private final boolean enabled;
    private final int requestsPerMinute;

    /** Key: "<partnerId>|<minute>". Entries outlive their window briefly, then expire. */
    private final Cache<String, AtomicInteger> counters = Caffeine.newBuilder()
            .expireAfterWrite(2, TimeUnit.MINUTES)
            .maximumSize(10_000)
            .build();

    public PartnerRateLimitFilter(
            @Value("${app.partner.rate-limit-enabled:true}") boolean enabled,
            @Value("${app.partner.rate-limit-per-minute:120}") int requestsPerMinute) {
        this.enabled = enabled;
        this.requestsPerMinute = requestsPerMinute;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request,
                                    HttpServletResponse response,
                                    FilterChain filterChain) throws ServletException, IOException {
        UUID partnerId = authenticatedPartnerId();
        if (!enabled || partnerId == null) {
            filterChain.doFilter(request, response);
            return;
        }

        String window = partnerId + "|" + LocalDateTime.now().truncatedTo(ChronoUnit.MINUTES);
        int used = counters.get(window, k -> new AtomicInteger()).incrementAndGet();

        if (used > requestsPerMinute) {
            log.warn("Rate limit exceeded for partner {} ({} requests this minute, limit {})",
                    partnerId, used, requestsPerMinute);
            writeTooManyRequests(response);
            return;
        }

        // Let a well-behaved client pace itself instead of discovering the limit by hitting it.
        response.setHeader("X-RateLimit-Limit", String.valueOf(requestsPerMinute));
        response.setHeader("X-RateLimit-Remaining", String.valueOf(Math.max(requestsPerMinute - used, 0)));
        filterChain.doFilter(request, response);
    }

    /** Only throttle the partner API; other chains never register this filter. */
    private UUID authenticatedPartnerId() {
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        if (authentication != null && authentication.getPrincipal() instanceof PartnerPrincipal principal) {
            return principal.partnerId();
        }
        return null;
    }

    private void writeTooManyRequests(HttpServletResponse response) throws IOException {
        response.setStatus(HttpStatus.TOO_MANY_REQUESTS.value());
        response.setContentType(MediaType.APPLICATION_JSON_VALUE);
        // Seconds until the current fixed window rolls over.
        response.setHeader("Retry-After", String.valueOf(60 - LocalDateTime.now().getSecond()));
        objectMapper.writeValue(response.getOutputStream(), Map.of(
                "success", false,
                "message", "Rate limit exceeded: at most " + requestsPerMinute
                        + " requests per minute. Please slow down and retry.",
                "timestamp", LocalDateTime.now().toString()));
    }
}
