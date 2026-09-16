package com.raaspal.robotrecommendation.auth.security;

import com.raaspal.robotrecommendation.auth.security.jwt.AuthEntryPointJwt;
import com.raaspal.robotrecommendation.auth.security.jwt.AuthTokenFilter;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.annotation.Order;
import org.springframework.security.authentication.AuthenticationManager;
import org.springframework.security.authentication.dao.DaoAuthenticationProvider;
import org.springframework.http.HttpMethod;
import org.springframework.security.config.annotation.authentication.configuration.AuthenticationConfiguration;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;
import org.springframework.web.cors.CorsConfiguration;
import org.springframework.web.cors.CorsConfigurationSource;
import org.springframework.web.cors.UrlBasedCorsConfigurationSource;

import java.util.Arrays;
import java.util.List;

/**
 * {@code @EnableMethodSecurity} switches on {@code @PreAuthorize}. Without it the
 * annotations parse fine and are simply never evaluated — inventory writes would
 * have been open to every authenticated user, including CUSTOMER. Nothing else in
 * the codebase uses method security yet, so enabling it changes no existing
 * behaviour; it only makes the annotations that do exist actually run.
 */
@Configuration
@EnableWebSecurity
@EnableMethodSecurity
@RequiredArgsConstructor
public class SecurityConfig {

    @Value("${app.cors.allowed-origins}")
    private String corsAllowedOrigins;

    private final UserDetailsServiceImpl userDetailsService;
    private final AuthEntryPointJwt authEntryPointJwt;
    private final AuthTokenFilter authTokenFilter;

    @Bean
    @Order(2)
    public SecurityFilterChain filterChain(HttpSecurity http) throws Exception {
        http
                .cors(cors -> cors.configurationSource(corsConfigurationSource()))
                .csrf(AbstractHttpConfigurer::disable)
                .sessionManagement(session ->
                        session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .exceptionHandling(ex ->
                        ex.authenticationEntryPoint(authEntryPointJwt))
                .authorizeHttpRequests(auth -> auth
                        // Auth endpoints are public
                        .requestMatchers("/api/v1/auth/login").permitAll()
                        // Public customer report links (the monthly email URL) — no account
                        .requestMatchers("/api/v1/reports/public/**").permitAll()
                        // The container health check. Docker polls this every 30s with
                        // no credentials; left authenticated it answered 401 and logged a
                        // WARN each time -- roughly 2,900 lines a day, enough to bury the
                        // sync and report lines somebody actually needs to read.
                        .requestMatchers("/actuator/health").permitAll()
                        // Swagger UI
                        .requestMatchers(
                                "/swagger-ui/**",
                                "/swagger-ui.html",
                                "/v3/api-docs/**"
                        ).permitAll()
                        // RIMS inventory surface. Reads are open to any signed-in
                        // staff member — a shared stock record is only useful if the
                        // team can see it — while writes are restricted per-method
                        // with @PreAuthorize on InventoryController, since reads and
                        // writes share a path and differ only by HTTP verb.
                        .requestMatchers("/api/v1/inventory/**").authenticated()
                        // Anything that emails a customer is for the RE team and
                        // admins only. A warehouse (INVENTORY_STAFF) login carries a
                        // valid JWT like any other, and before this rule that was
                        // enough to start the monthly delivery run. Enforced here by
                        // URL rather than with @PreAuthorize on the methods: method
                        // security runs after Spring has already resolved the
                        // request params and body, so a denial and a missing param
                        // are indistinguishable without actually triggering a send -
                        // which is also why the filter-level rule is the testable one.
                        .requestMatchers(HttpMethod.POST,
                                "/api/v1/reports/delivery/run",
                                "/api/v1/reports/delivery/send",
                                "/api/v1/reports/email",
                                "/api/v1/customers/announcements"
                        ).hasAnyRole("ADMIN", "RAASPAL_TEAM")
                        // Everything else requires a valid JWT
                        .anyRequest().authenticated()
                )
                .authenticationProvider(authenticationProvider())
                .addFilterBefore(authTokenFilter, UsernamePasswordAuthenticationFilter.class);

        return http.build();
    }

    @Bean
    public DaoAuthenticationProvider authenticationProvider() {
        DaoAuthenticationProvider provider = new DaoAuthenticationProvider();
        provider.setUserDetailsService(userDetailsService);
        provider.setPasswordEncoder(passwordEncoder());
        return provider;
    }

    @Bean
    public AuthenticationManager authenticationManager(AuthenticationConfiguration config) throws Exception {
        return config.getAuthenticationManager();
    }

    @Bean
    public PasswordEncoder passwordEncoder() {
        return new BCryptPasswordEncoder();
    }

    @Bean
    public CorsConfigurationSource corsConfigurationSource() {
        CorsConfiguration config = new CorsConfiguration();
        List<String> origins = Arrays.stream(corsAllowedOrigins.split(","))
                .map(String::trim)
                .filter(s -> !s.isEmpty())
                .toList();
        config.setAllowedOrigins(origins);
        config.setAllowedMethods(List.of("GET", "POST", "PUT", "PATCH", "DELETE", "OPTIONS"));
        config.setAllowedHeaders(List.of("*"));
        config.setAllowCredentials(true);

        UrlBasedCorsConfigurationSource source = new UrlBasedCorsConfigurationSource();
        source.registerCorsConfiguration("/**", config);
        return source;
    }
}
