package com.raaspal.robotrecommendation.partner;

import com.raaspal.robotrecommendation.partner.repository.PartnerApiAccessLogRepository;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.context.ApplicationContext;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.filter.OncePerRequestFilter;

import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

/**
 * The partner filters must run <strong>only</strong> on the partner chain.
 *
 * <p>This is not hypothetical tidiness. Spring Boot auto-registers every bean of
 * type {@link jakarta.servlet.Filter} with the servlet container at {@code /*},
 * which happens <em>independently</em> of
 * {@code HttpSecurity.securityMatcher("/api/partner/**")}. A filter annotated
 * {@code @Component} and also wired into the partner chain therefore runs in two
 * places: where it was intended, and on every other request the application
 * serves. Nothing in the security config hints at this, and no existing test
 * noticed, because from the partner API's own point of view everything works.
 *
 * <p>The container copies are ordered after {@code springSecurityFilterChain}, so
 * this was never an authorisation bypass — by the time they run, the staff chain
 * has already allowed or rejected the request. The damage is to the audit log and
 * to the database: see {@link #staffRequestsAreNotWrittenToThePartnerAuditLog()}.
 */
@SpringBootTest
@AutoConfigureMockMvc
@Transactional
class PartnerFilterScopeTest {

    @Autowired
    private MockMvc mockMvc;
    @Autowired
    private ApplicationContext applicationContext;
    @Autowired
    private PartnerApiAccessLogRepository accessLogRepository;

    /**
     * The defect with a visible cost. {@code PartnerAccessAuditFilter} has no
     * partner-path check and is enabled by default, so the container-registered
     * copy wrote a row for every non-partner request that passed authorisation —
     * staff API calls, Swagger, and Render's health pings.
     *
     * <p>Two consequences. One insert per request against the free Supabase tier,
     * for requests the table was never meant to describe. And every such row has
     * {@code partner_id = NULL}, which is indistinguishable from a failed partner
     * authentication — the exact signal the table exists to surface. Audit noise of
     * that shape does not merely waste space, it hides the thing being audited.
     */
    @Test
    void staffRequestsAreNotWrittenToThePartnerAuditLog() throws Exception {
        long before = accessLogRepository.count();

        // A permitAll staff route: the request reaches the controller, so the whole
        // servlet filter chain runs. (A 401 would be rejected inside the security
        // chain and never reach a container-registered filter at all.)
        mockMvc.perform(post("/api/v1/auth/login")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"email\":\"nobody@example.com\",\"password\":\"wrong\"}"));

        assertThat(accessLogRepository.count())
                .as("a staff request must leave no trace in partner_api_access_logs")
                .isEqualTo(before);
    }

    /**
     * Guards the fix generally rather than naming today's four filters: any future
     * filter added to {@code partner.security} is caught here unless it is
     * explicitly kept out of the container. Written this way because the mistake is
     * so easy to repeat — {@code @Component} on a filter is the ordinary thing to
     * do everywhere else in the codebase.
     */
    @Test
    void everyPartnerFilterIsExcludedFromTheServletContainer() {
        Map<String, OncePerRequestFilter> partnerFilters =
                applicationContext.getBeansOfType(OncePerRequestFilter.class).entrySet().stream()
                        .filter(e -> e.getValue().getClass().getPackageName()
                                .startsWith("com.raaspal.robotrecommendation.partner"))
                        .collect(Collectors.toMap(Map.Entry::getKey, Map.Entry::getValue));

        assertThat(partnerFilters)
                .as("sanity check: the partner filters should exist as beans")
                .isNotEmpty();

        Set<OncePerRequestFilter> keptOut = applicationContext
                .getBeansOfType(FilterRegistrationBean.class).values().stream()
                .filter(registration -> !registration.isEnabled())
                .map(FilterRegistrationBean::getFilter)
                .filter(OncePerRequestFilter.class::isInstance)
                .map(OncePerRequestFilter.class::cast)
                .collect(Collectors.toSet());

        List<String> stillRegistered = partnerFilters.entrySet().stream()
                .filter(e -> !keptOut.contains(e.getValue()))
                .map(Map.Entry::getKey)
                .sorted()
                .toList();

        assertThat(stillRegistered)
                .as("these partner filters would run on every request in the application; "
                        + "add a disabled FilterRegistrationBean for each in PartnerSecurityConfig")
                .isEmpty();
    }
}
