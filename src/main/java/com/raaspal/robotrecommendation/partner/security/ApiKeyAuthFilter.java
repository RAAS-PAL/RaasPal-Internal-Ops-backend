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
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.util.List;

/**
 * Authenticates partner-API requests by the {@code X-API-Key} header. A valid,
 * active key belonging to an active partner is turned into a {@link PartnerPrincipal}
 * in the SecurityContext; anything else leaves the context empty and the security
 * chain answers 401 via {@code PartnerAuthEntryPoint}.
 *
 * <p>Deliberately mirrors {@code AuthTokenFilter} (the JWT filter): it never throws
 * — a bad key simply means "not authenticated" — so a malformed header can never
 * 500. It only runs on the partner chain, which is matched to {@code /api/partner/**}.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class ApiKeyAuthFilter extends OncePerRequestFilter {

    public static final String API_KEY_HEADER = "X-API-Key";

    /**
     * Request attribute holding the authenticated {@link PartnerPrincipal}.
     * {@link PartnerAccessAuditFilter} reads this rather than the SecurityContext
     * because it runs outermost, and Spring Security clears the context on the way
     * out — a request attribute lives for the whole request regardless.
     */
    public static final String PARTNER_PRINCIPAL_ATTRIBUTE = "raaspal.partnerPrincipal";

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
        request.setAttribute(PARTNER_PRINCIPAL_ATTRIBUTE, principal);
    }
}
