package com.raaspal.robotrecommendation.partner.security;

import com.raaspal.robotrecommendation.partner.service.PartnerApiKeyService;
import com.raaspal.robotrecommendation.partner.service.PartnerApiKeyService.AuthenticatedPartner;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.web.authentication.WebAuthenticationDetailsSource;
import org.springframework.util.StringUtils;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.util.List;

/**
 * Authenticates partner-API requests by the {@code X-API-Key} header, turning a
 * valid, active key belonging to an active partner into a {@link PartnerPrincipal}.
 *
 * <p><strong>RETIRED — not wired into any security chain, and deliberately not a
 * bean.</strong> The partner API authenticates with OAuth bearer tokens via
 * {@link PartnerJwtAuthFilter}; raw keys are now only ever exchanged for a token at
 * the token endpoint. The class is kept for one release so the previous scheme can
 * be restored quickly if the cutover needs reverting — re-add {@code @Component},
 * an {@code addFilterBefore} in {@link PartnerSecurityConfig}, and a disabled
 * registration alongside the others.
 *
 * <p>The missing {@code @Component} is the load-bearing part. Spring Boot registers
 * every {@code Filter} bean with the servlet container at {@code /*}, so while this
 * was a bean it kept running on every request in the application despite belonging
 * to no chain: an {@code X-API-Key} header on any staff path still cost a credential
 * lookup and a {@code last_used_at} write, driven entirely by a caller-supplied
 * header. It could not grant access — the container copies run after authorisation —
 * but a retired credential should not be doing database work at all.
 */
@Slf4j
@RequiredArgsConstructor
public class ApiKeyAuthFilter extends OncePerRequestFilter {

    public static final String API_KEY_HEADER = "X-API-Key";

    private static final String PARTNER_AUTHORITY = "ROLE_PARTNER";

    private final PartnerApiKeyService partnerApiKeyService;

    @Override
    protected void doFilterInternal(HttpServletRequest request,
                                    HttpServletResponse response,
                                    FilterChain filterChain) throws ServletException, IOException {
        try {
            String apiKey = request.getHeader(API_KEY_HEADER);
            if (StringUtils.hasText(apiKey)
                    && SecurityContextHolder.getContext().getAuthentication() == null) {
                partnerApiKeyService.authenticate(apiKey).ifPresent(partner -> {
                    authenticate(request, partner);
                    // Best-effort audit; never let a write failure break the request.
                    try {
                        partnerApiKeyService.touchLastUsed(partner.keyId());
                    } catch (Exception e) {
                        log.warn("Could not update last-used for partner key {}: {}",
                                partner.keyId(), e.getMessage());
                    }
                });
            }
        } catch (Exception ex) {
            // Never leak the key or fail open — just proceed unauthenticated.
            log.error("Partner API-key authentication error: {}", ex.getMessage());
        }
        filterChain.doFilter(request, response);
    }

    private void authenticate(HttpServletRequest request, AuthenticatedPartner partner) {
        PartnerPrincipal principal =
                new PartnerPrincipal(partner.partnerId(), partner.partnerName(), partner.keyId());
        UsernamePasswordAuthenticationToken authentication =
                new UsernamePasswordAuthenticationToken(
                        principal,
                        null,
                        List.of(new SimpleGrantedAuthority(PARTNER_AUTHORITY)));
        authentication.setDetails(new WebAuthenticationDetailsSource().buildDetails(request));
        SecurityContextHolder.getContext().setAuthentication(authentication);
        request.setAttribute(PartnerPrincipal.REQUEST_ATTRIBUTE, principal);
    }
}
