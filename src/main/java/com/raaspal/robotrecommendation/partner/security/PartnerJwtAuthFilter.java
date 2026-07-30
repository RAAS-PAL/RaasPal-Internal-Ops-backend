package com.raaspal.robotrecommendation.partner.security;

import com.raaspal.robotrecommendation.partner.service.PartnerApiKeyService;
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
 * Authenticates partner-API requests by the {@code Authorization: Bearer} token
 * issued at {@code /api/partner/v1/oauth/token}.
 *
 * <p>Produces exactly the same {@link PartnerPrincipal} that
 * {@link ApiKeyAuthFilter} did, so scoping, rate limiting and the access audit
 * are unaffected by the change of credential.
 *
 * <p><strong>The stored credential is re-checked on every request.</strong> A JWT
 * is self-contained and would otherwise stay valid for its full hour even after
 * an admin revoked the key — meaning revocation would not bite until expiry. We
 * already query the database for scoping on these endpoints, so verifying the
 * key is still usable costs one extra lookup and keeps revocation instant.
 *
 * <p>Like the filter it replaces, this never throws: an absent, malformed or
 * expired token simply leaves the request unauthenticated for the chain to
 * reject, rather than producing a 500.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class PartnerJwtAuthFilter extends OncePerRequestFilter {

    private static final String BEARER_PREFIX = "Bearer ";
    private static final String PARTNER_AUTHORITY = "ROLE_PARTNER";

    private final PartnerTokenService partnerTokenService;
    private final PartnerApiKeyService partnerApiKeyService;

    @Override
    protected void doFilterInternal(HttpServletRequest request,
                                    HttpServletResponse response,
                                    FilterChain filterChain) throws ServletException, IOException {
        try {
            String token = bearerToken(request);
            if (token != null && SecurityContextHolder.getContext().getAuthentication() == null) {
                partnerTokenService.parse(token)
                        // Revoked, expired or belonging to a disabled partner: the
                        // token may still be signed and unexpired, but the credential
                        // behind it is no longer valid.
                        .filter(principal -> partnerApiKeyService.isKeyStillUsable(principal.apiKeyId()))
                        .ifPresent(principal -> authenticate(request, principal));
            }
        } catch (Exception ex) {
            // Never leak token contents, and never fail open.
            log.error("Partner token authentication error: {}", ex.getMessage());
        }
        filterChain.doFilter(request, response);
    }

    private String bearerToken(HttpServletRequest request) {
        String header = request.getHeader("Authorization");
        if (StringUtils.hasText(header) && header.startsWith(BEARER_PREFIX)) {
            return header.substring(BEARER_PREFIX.length()).trim();
        }
        return null;
    }

    private void authenticate(HttpServletRequest request, PartnerPrincipal principal) {
        UsernamePasswordAuthenticationToken authentication =
                new UsernamePasswordAuthenticationToken(
                        principal, null, List.of(new SimpleGrantedAuthority(PARTNER_AUTHORITY)));
        authentication.setDetails(new WebAuthenticationDetailsSource().buildDetails(request));
        SecurityContextHolder.getContext().setAuthentication(authentication);
        // Read back by PartnerAccessAuditFilter, which runs outermost and would
        // otherwise find the SecurityContext already cleared.
        request.setAttribute(PartnerPrincipal.REQUEST_ATTRIBUTE, principal);
    }
}
