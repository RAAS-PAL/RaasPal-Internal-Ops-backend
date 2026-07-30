package com.raaspal.robotrecommendation.partner.security;

import jakarta.servlet.Filter;
import lombok.RequiredArgsConstructor;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.annotation.Order;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.access.intercept.AuthorizationFilter;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;
import org.springframework.security.web.context.request.async.WebAsyncManagerIntegrationFilter;

/**
 * A dedicated security chain for the partner-facing API ({@code /api/partner/**}),
 * fully separate from the staff JWT chain in {@code SecurityConfig}. Callers
 * exchange a client id and secret for a bearer token at
 * {@code /api/partner/v1/oauth/token} and authenticate with that token — no staff
 * JWT, no session, no form login.
 *
 * <p>{@code @Order(1)} makes this chain win for {@code /api/partner/**}; every other
 * path falls through to the staff chain ({@code @Order(2)}). Keeping the two apart,
 * and giving partner tokens their own signing secret, means a partner token can
 * never reach a staff endpoint and a staff JWT can never reach a partner endpoint.
 *
 * <p>Hardening choices: stateless (no session fixation surface), CSRF disabled
 * (there is no cookie/session to forge — auth is a header secret), and no CORS
 * (this is a server-to-server API, not called from a browser).
 */
@Configuration
@RequiredArgsConstructor
public class PartnerSecurityConfig {

    private final PartnerJwtAuthFilter partnerJwtAuthFilter;
    private final PartnerRateLimitFilter partnerRateLimitFilter;
    private final PartnerAccessAuditFilter partnerAccessAuditFilter;
    private final PartnerAuthEntryPoint partnerAuthEntryPoint;

    // @Order MUST sit on the @Bean method: for SecurityFilterChain beans it is the
    // method-level order that sorts the chains. A class-level @Order on the config
    // is ignored here, which would let the catch-all JWT chain publish first and
    // make this partner chain unreachable (Spring rejects that at startup).
    @Bean
    @Order(1)
    public SecurityFilterChain partnerFilterChain(HttpSecurity http) throws Exception {
        http
                .securityMatcher("/api/partner/**")
                .csrf(AbstractHttpConfigurer::disable)
                .cors(AbstractHttpConfigurer::disable)
                .formLogin(AbstractHttpConfigurer::disable)
                .httpBasic(AbstractHttpConfigurer::disable)
                .sessionManagement(session ->
                        session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .exceptionHandling(ex ->
                        ex.authenticationEntryPoint(partnerAuthEntryPoint))
                .authorizeHttpRequests(auth -> auth
                        // The token endpoint is what callers use BEFORE they hold a
                        // token, so it cannot itself require one.
                        .requestMatchers("/api/partner/v1/oauth/token").permitAll()
                        .anyRequest().authenticated())
                // Filter order matters, and each is anchored to a well-known filter
                // so the resulting order is unambiguous:
                //   audit (outermost, sees the final status incl. 401/429)
                //     → bearer-token auth
                //       → rate limit (needs the authenticated partner to meter per key)
                .addFilterBefore(partnerAccessAuditFilter, WebAsyncManagerIntegrationFilter.class)
                .addFilterBefore(partnerJwtAuthFilter, UsernamePasswordAuthenticationFilter.class)
                .addFilterBefore(partnerRateLimitFilter, AuthorizationFilter.class);

        return http.build();
    }

    /*
     * Keeping the partner filters off every other request.
     *
     * Spring Boot registers each bean of type Filter with the servlet container at
     * "/*", and that happens independently of securityMatcher above — being added to
     * this chain does not remove a filter from the container, it adds a second place
     * the filter runs. So each @Component filter here also ran on every staff
     * request, Swagger page and health ping the application served.
     *
     * The container copies sort after springSecurityFilterChain, so this was never an
     * authorisation bypass: by the time they ran, the staff chain had already allowed
     * or rejected the request. The cost was the audit log. PartnerAccessAuditFilter
     * has no partner-path check, so it wrote a row per non-partner request — an insert
     * apiece against the free Supabase tier, every one of them with partner_id NULL,
     * which is exactly what a failed partner authentication looks like. Audit noise in
     * that shape hides the probing attempts the table exists to reveal.
     *
     * Disabling the registration leaves the bean untouched for Spring Security's use
     * while removing it from the container. PartnerFilterScopeTest fails if a new
     * partner filter is added without one of these.
     */

    @Bean
    public FilterRegistrationBean<PartnerAccessAuditFilter> partnerAccessAuditFilterNotGlobal() {
        return notGlobal(partnerAccessAuditFilter);
    }

    @Bean
    public FilterRegistrationBean<PartnerJwtAuthFilter> partnerJwtAuthFilterNotGlobal() {
        return notGlobal(partnerJwtAuthFilter);
    }

    @Bean
    public FilterRegistrationBean<PartnerRateLimitFilter> partnerRateLimitFilterNotGlobal() {
        return notGlobal(partnerRateLimitFilter);
    }

    private static <T extends Filter> FilterRegistrationBean<T> notGlobal(T filter) {
        FilterRegistrationBean<T> registration = new FilterRegistrationBean<>(filter);
        registration.setEnabled(false);
        return registration;
    }
}
