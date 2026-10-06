package com.raaspal.robotrecommendation.knowledge;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.raaspal.robotrecommendation.auth.security.jwt.JwtUtils;
import com.raaspal.robotrecommendation.common.enums.Role;
import com.raaspal.robotrecommendation.user.entity.User;
import com.raaspal.robotrecommendation.user.repository.UserRepository;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.test.autoconfigure.web.servlet.*;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.*;
import org.springframework.mail.MailSendException;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.*;
import org.springframework.web.servlet.mvc.method.annotation.RequestMappingHandlerMapping;

import java.time.Instant;
import java.util.*;
import java.util.concurrent.*;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/** Real H2 transactions (no enclosing rollback) pin counters and single-use under races. */
@SpringBootTest(properties = "app.kc.accounts.enabled=true")
@AutoConfigureMockMvc(print = MockMvcPrint.NONE)
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
class KcAccountsTest {
    static final String BASE = "/api/v1/kc/auth";
    static final String PASSWORD = "test-password-123";
    @Autowired MockMvc mvc;
    @Autowired ObjectMapper json;
    @Autowired EmailCodeRepository codes;
    @Autowired UserRepository users;
    @Autowired PasswordEncoder passwords;
    @Autowired JwtUtils jwt;
    @Autowired KcAuthService service;
    @Autowired @Qualifier("requestMappingHandlerMapping") RequestMappingHandlerMapping mappings;
    @MockitoBean KcCodeMailer mail;
    final Map<String, String> sent = new ConcurrentHashMap<>();
    String email;

    @BeforeEach void setup() {
        codes.deleteAll();
        email = "kc-test-" + UUID.randomUUID() + "@raaspal.com";
        sent.clear();
        doAnswer(call -> { sent.put(call.getArgument(0), call.getArgument(2)); return null; })
                .when(mail).send(anyString(), any(), anyString());
    }

    ResultActions postJson(String path, Object body) throws Exception {
        return mvc.perform(post(path).contentType(MediaType.APPLICATION_JSON).content(json.writeValueAsBytes(body)));
    }
    JsonNode data(ResultActions response) throws Exception {
        return json.readTree(response.andExpect(status().isOk()).andReturn().getResponse().getContentAsString()).path("data");
    }
    void error(ResultActions response, String code) throws Exception {
        response.andExpect(status().is4xxClientError()).andExpect(jsonPath("$.success").value(false))
                .andExpect(jsonPath("$.message").value(code));
    }
    ResultActions send(String purpose, String address) throws Exception {
        return postJson(BASE + "/send-code", Map.of("purpose", purpose, "email", address));
    }
    ResultActions verify(String purpose, String address, String code) throws Exception {
        return postJson(BASE + "/verify-code", Map.of("purpose", purpose, "email", address, "code", code));
    }
    String ticket(String purpose) throws Exception {
        send(purpose, email).andExpect(status().isOk());
        return data(verify(purpose, email, sent.get(email))).path("ticket").asText();
    }
    ResultActions signup(String ticket, String name, String password) throws Exception {
        return postJson(BASE + "/signup", Map.of("ticket", ticket, "name", name, "password", password));
    }
    User account(String address, Role role) {
        return users.saveAndFlush(User.builder().email(address).fullName("Test employee")
                .password(passwords.encode(PASSWORD)).role(role).build());
    }
    void allowResend() {
        EmailCode slot = codes.findById(email).orElseThrow();
        slot.setCreatedAt(Instant.now().minusSeconds(61));
        codes.saveAndFlush(slot);
    }

    @Test void sendErrorsAndNormalization() throws Exception {
        error(send("signup", ""), "invalid");
        error(send("signup", "bad@@raaspal.com"), "invalid");
        error(send("bad", email), "invalid");
        error(send("signup", "test@example.org"), "domain");
        error(send("signup", "test@raaspal.com.evil.org"), "domain");
        send("SIGNUP", "  " + email.toUpperCase(Locale.ROOT) + "  ")
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.email").value(email));
        assertThat(sent.containsKey(email)).isTrue();
        EmailCode row = codes.findById(email).orElseThrow();
        assertThat(row.getCodeHash().equals(sent.get(email))).isFalse();
        assertThat(passwords.matches(sent.get(email), row.getCodeHash())).isTrue();
        assertThat(row.getExpiresAt()).isEqualTo(row.getCreatedAt().plusSeconds(600));
    }

    @Test void signupRefusesExistingIncludingDisabledAndMixedCase() throws Exception {
        User user = account(email.toUpperCase(Locale.ROOT), Role.ADMIN);
        error(send("signup", email), "exists");
        user.setActive(false); users.saveAndFlush(user);
        error(send("signup", email), "exists");
        verifyNoInteractions(mail);
    }

    @Test void resetUnknownAndDisabledReturnsOkWithoutMail() throws Exception {
        send("reset", email).andExpect(status().isOk());
        error(send("reset", email), "unavailable");
        verifyNoInteractions(mail);
        allowResend();
        User user = account(email, Role.ADMIN); user.setActive(false); users.saveAndFlush(user);
        send("reset", email).andExpect(status().isOk());
        verifyNoInteractions(mail);
    }

    @Test void resendCooldownHourlyCapAndReplacement() throws Exception {
        String oldTicket = ticket("signup");
        send("signup", email).andExpect(status().isTooManyRequests()).andExpect(header().exists("Retry-After"));
        for (int n = 1; n < 5; n++) {
            allowResend(); send("signup", email).andExpect(status().isOk());
        }
        error(signup(oldTicket, "Test employee", PASSWORD), "expired");
        allowResend();
        send("reset", email).andExpect(status().isTooManyRequests());
        EmailCode row = codes.findById(email).orElseThrow();
        row.setWindowStartedAt(Instant.now().minusSeconds(3601)); codes.saveAndFlush(row);
        send("signup", email).andExpect(status().isOk());
        assertThat(codes.findById(email).orElseThrow().getSendCount()).isEqualTo(1);
    }

    @Test void wrongGuessesCommitAndFifthAttemptLocksCode() throws Exception {
        send("signup", email).andExpect(status().isOk());
        for (int n = 0; n < 5; n++) error(verify("signup", email, "not-six-digits"), "wrong");
        assertThat(codes.findById(email).orElseThrow().getTries()).isEqualTo(5);
        error(verify("signup", email, sent.get(email)), "expired");
    }

    @Test void fifthAttemptMaySucceedAndCodeCannotBeReused() throws Exception {
        send("signup", email).andExpect(status().isOk());
        for (int n = 0; n < 4; n++) error(verify("signup", email, "bad"), "wrong");
        verify("SIGNUP", email.toUpperCase(Locale.ROOT), sent.get(email)).andExpect(status().isOk());
        error(verify("signup", email, sent.get(email)), "expired");
    }

    @Test void verifyErrorsAndExpiredCode() throws Exception {
        error(verify("signup", "test@example.org", "123456"), "wrong");
        error(verify("unknown", email, "123456"), "wrong");
        error(verify("signup", email, "123456"), "expired");
        send("signup", email).andExpect(status().isOk());
        error(verify("reset", email, sent.get(email)), "expired");
        EmailCode row = codes.findById(email).orElseThrow();
        row.setExpiresAt(Instant.now().minusSeconds(1)); codes.saveAndFlush(row);
        error(verify("signup", email, sent.get(email)), "expired");
    }

    @Test void signupValidationAndSingleUseSession() throws Exception {
        String ticket = ticket("signup");
        assertThat(codes.findById(email).orElseThrow().getTicketHash().equals(ticket)).isFalse();
        error(signup(ticket, "  ", PASSWORD), "name");
        error(signup(ticket, "Test employee", "short"), "short");
        error(postJson(BASE + "/signup", Map.of("ticket", ticket, "name", "Test employee",
                "password", PASSWORD, "confirm", "different")), "mismatch");
        error(signup(ticket, "Test employee", "x".repeat(73)), "unavailable");
        JsonNode auth = data(signup(ticket, "  Test   employee  ", PASSWORD));
        assertThat(auth.path("user").path("role").asText()).isEqualTo("STAFF");
        assertThat(auth.path("tokenType").asText()).isEqualTo("Bearer");
        User user = users.findByEmailIgnoreCase(email).orElseThrow();
        assertThat(user.getFullName()).isEqualTo("Test employee");
        assertThat(user.getKcRole()).isEqualTo(KcRole.VIEWER);
        assertThat(passwords.matches(PASSWORD, user.getPassword())).isTrue();
        String token = auth.path("accessToken").asText();
        mvc.perform(get(BASE + "/me").header("Authorization", "Bearer " + token))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.kc_role").value("VIEWER"));
        mvc.perform(get("/api/v1/robots").header("Authorization", "Bearer " + token)).andExpect(status().isForbidden());
        mvc.perform(get("/api/v1/inventory/items").header("Authorization", "Bearer " + token)).andExpect(status().isForbidden());
        error(signup(ticket, "Test employee", PASSWORD), "expired");
        error(send("signup", email), "exists");
    }

    @Test void expiredTicketAndWrongPurposeCannotFinish() throws Exception {
        String ticket = ticket("signup");
        error(postJson(BASE + "/reset", Map.of("ticket", ticket, "password", PASSWORD)), "expired");
        EmailCode row = codes.findById(email).orElseThrow();
        row.setTicketExpiresAt(Instant.now().minusSeconds(1)); codes.saveAndFlush(row);
        error(signup(ticket, "Test employee", PASSWORD), "expired");
        error(signup("garbage", "Test employee", PASSWORD), "expired");
    }

    @Test void resetPreservesExistingRolesAndPasswordWorksInSharedLogin() throws Exception {
        User user = account(email.toUpperCase(Locale.ROOT), Role.ADMIN);
        user.setKcRole(KcRole.EDITOR); users.saveAndFlush(user);
        String ticket = ticket("reset");
        error(postJson(BASE + "/reset", Map.of("ticket", ticket, "password", "short")), "short");
        error(postJson(BASE + "/reset", Map.of("ticket", ticket, "password", PASSWORD, "confirm", "different")), "mismatch");
        data(postJson(BASE + "/reset", Map.of("ticket", ticket, "password", "replacement-password")));
        error(postJson(BASE + "/reset", Map.of("ticket", ticket, "password", PASSWORD)), "expired");
        User updated = users.findById(user.getId()).orElseThrow();
        assertThat(updated.getRole()).isEqualTo(Role.ADMIN);
        assertThat(updated.getKcRole()).isEqualTo(KcRole.EDITOR);
        error(postJson("/api/v1/auth/login", Map.of("email", email, "password", PASSWORD)), "credentials");
        postJson("/api/v1/auth/login", Map.of("email", email, "password", "replacement-password"))
                .andExpect(status().isOk());
    }

    @Test void loginCompanyAudienceAndMeEnforceDomain() throws Exception {
        User outsider = account("kc-test-" + UUID.randomUUID() + "@example.org", Role.ADMIN);
        postJson("/api/v1/auth/login", Map.of("email", outsider.getEmail(), "password", PASSWORD)).andExpect(status().isOk());
        error(postJson("/api/v1/auth/login", Map.of("email", outsider.getEmail(), "password", PASSWORD, "audience", "kc")), "domain");
        mvc.perform(get(BASE + "/me").header("Authorization", "Bearer " + jwt.generateToken(outsider.getEmail())))
                .andExpect(status().isForbidden()).andExpect(jsonPath("$.message").value("domain"));
        mvc.perform(get(BASE + "/me")).andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.message").value("credentials"));
        error(postJson("/api/v1/auth/login", Map.of()), "invalid");
        error(postJson("/api/v1/auth/login", Map.of("email", email, "password", "")), "credentials");
        User inside = account(email, Role.RAASPAL_TEAM);
        postJson("/api/v1/auth/login", Map.of("email", email.toUpperCase(Locale.ROOT), "password", PASSWORD, "audience", "kc"))
                .andExpect(status().isOk());
        assertThat(users.findById(inside.getId()).orElseThrow().getKcRole()).isEqualTo(KcRole.VIEWER);
    }

    @Test void meInitializesOnlyNullKcRoleAndKeepsOpsRole() throws Exception {
        User user = account(email, Role.INVENTORY_STAFF);
        String token = jwt.generateToken(email);
        mvc.perform(get(BASE + "/me").header("Authorization", "Bearer " + token))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.kc_role").value("VIEWER"));
        user = users.findById(user.getId()).orElseThrow();
        assertThat(user.getRole()).isEqualTo(Role.INVENTORY_STAFF);
        user.setKcRole(KcRole.ADMIN); users.saveAndFlush(user);
        mvc.perform(get(BASE + "/me").header("Authorization", "Bearer " + token))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.kc_role").value("ADMIN"));
    }

    @Test void disabledAccountCannotResetEvenWithEarlierTicket() throws Exception {
        User user = account(email, Role.RAASPAL_TEAM);
        String ticket = ticket("reset");
        user.setActive(false); users.saveAndFlush(user);
        error(postJson(BASE + "/reset", Map.of("ticket", ticket, "password", PASSWORD)), "expired");
    }

    @Test void mailFailureIsCodedAndRollsBackReplacement() throws Exception {
        String ticket = ticket("signup");
        allowResend();
        doThrow(new MailSendException("sensitive transport details")).when(mail).send(anyString(), any(), anyString());
        send("signup", email).andExpect(status().isServiceUnavailable()).andExpect(jsonPath("$.message").value("unavailable"));
        signup(ticket, "Test employee", PASSWORD).andExpect(status().isOk());
    }

    @Test void globalCapDoesNotReplaceAnAlreadyVerifiedTicket() throws Exception {
        String ticket = ticket("signup");
        allowResend();
        EmailCode budget = codes.findById(KcCodeEmailBudget.KEY).orElseThrow();
        budget.setSendCount(60); codes.saveAndFlush(budget);
        send("signup", email).andExpect(status().isTooManyRequests())
                .andExpect(jsonPath("$.message").value("unavailable"));
        signup(ticket, "Test employee", PASSWORD).andExpect(status().isOk());
        org.mockito.Mockito.verify(mail, times(1)).send(eq(email), any(), anyString());
    }

    @Test void malformedBodiesNeverEchoSecrets() throws Exception {
        error(postJson(BASE + "/send-code", Map.of()), "invalid");
        error(postJson(BASE + "/verify-code", Map.of()), "wrong");
        mvc.perform(post(BASE + "/signup").contentType(MediaType.APPLICATION_JSON).content("{broken"))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.message").value("expired"));
    }

    @Test void concurrentTicketUseCreatesExactlyOneAccount() throws Exception {
        String ticket = ticket("signup");
        try (var pool = Executors.newFixedThreadPool(2)) {
            var start = new CountDownLatch(1);
            Callable<Boolean> call = () -> {
                start.await();
                try { service.signUp(ticket, "Test employee", PASSWORD, null); return true; }
                catch (KcAuthException ex) { assertThat(ex.getMessage()).isEqualTo("expired"); return false; }
            };
            var a = pool.submit(call); var b = pool.submit(call); start.countDown();
            assertThat(List.of(a.get(20, TimeUnit.SECONDS), b.get(20, TimeUnit.SECONDS)))
                    .containsExactlyInAnyOrder(true, false);
        }
    }

    @Test void concurrentFirstSendRespectsCooldown() throws Exception {
        try (var pool = Executors.newFixedThreadPool(2)) {
            var start = new CountDownLatch(1);
            Callable<Boolean> call = () -> {
                start.await();
                try { service.sendCode("signup", email); return true; }
                catch (KcAuthException ex) { assertThat(ex.getStatus()).isEqualTo(429); return false; }
            };
            var a = pool.submit(call); var b = pool.submit(call); start.countDown();
            assertThat(List.of(a.get(20, TimeUnit.SECONDS), b.get(20, TimeUnit.SECONDS)))
                    .containsExactlyInAnyOrder(true, false);
            org.mockito.Mockito.verify(mail, times(1)).send(eq(email), any(), anyString());
        }
    }

    @Test @WithMockUser(roles = "STAFF")
    void staffIsForbiddenOnEveryMappedLegacyApiOperation() throws Exception {
        int checked = 0;
        for (var mapping : mappings.getHandlerMethods().keySet()) {
            for (String pattern : mapping.getPatternValues()) {
                if (!pattern.startsWith("/api/v1/") || pattern.startsWith("/api/v1/kc/")
                        || pattern.equals("/api/v1/auth/login")) continue;
                String path = pattern.replaceAll("\\{[^}]+}", "00000000-0000-0000-0000-000000000001")
                        .replace("**", "test").replace("*", "test");
                for (var method : mapping.getMethodsCondition().getMethods()) {
                    mvc.perform(request(HttpMethod.valueOf(method.name()), path)).andExpect(status().isForbidden());
                    checked++;
                }
            }
        }
        assertThat(checked).isGreaterThan(100);
        System.out.println("KC security: STAFF denied on " + checked + " mapped legacy API operations");
    }
}
