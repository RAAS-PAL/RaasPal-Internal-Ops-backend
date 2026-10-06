package com.raaspal.robotrecommendation.knowledge;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.raaspal.robotrecommendation.common.enums.Role;
import com.raaspal.robotrecommendation.user.entity.User;
import com.raaspal.robotrecommendation.user.repository.UserRepository;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.*;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.mail.MailSendException;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.*;
import org.springframework.transaction.PlatformTransactionManager;

import java.time.*;
import java.util.*;
import java.util.concurrent.*;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest(properties = {"app.kc.accounts.enabled=true", "app.kc.codes.max-per-hour=2"})
@AutoConfigureMockMvc(print = MockMvcPrint.NONE)
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
class KcCodeEmailBudgetTest {
    @Autowired MockMvc mvc;
    @Autowired ObjectMapper json;
    @Autowired EmailCodeRepository codes;
    @Autowired EmailCodeSlot slots;
    @Autowired UserRepository users;
    @Autowired KcCodeEmailBudget budget;
    @Autowired KcAuthService accounts;
    @Autowired PlatformTransactionManager transactions;
    @MockitoBean KcCodeMailer mail;

    @BeforeEach void resetBudget() { codes.deleteAll(); }

    String address() { return "kc-budget-" + UUID.randomUUID() + "@raaspal.com"; }

    ResultActions send(String purpose, String email) throws Exception {
        return mvc.perform(post("/api/v1/kc/auth/send-code").contentType(MediaType.APPLICATION_JSON)
                .content(json.writeValueAsBytes(Map.of("purpose", purpose, "email", email))));
    }

    void capped(ResultActions result) throws Exception {
        var response = result.andExpect(status().isTooManyRequests())
                .andExpect(jsonPath("$.message").value("unavailable"))
                .andExpect(header().exists("Retry-After")).andReturn().getResponse();
        assertThat(Long.parseLong(response.getHeader("Retry-After"))).isBetween(1L, 3601L);
    }

    @Test void differentEmailsAndBothPurposesShareOneCap() throws Exception {
        String resetEmail = address();
        users.saveAndFlush(User.builder().email(resetEmail).fullName("Budget test")
                .password("unused-test-hash").role(Role.STAFF).build());
        send("signup", address()).andExpect(status().isOk());
        send("reset", resetEmail).andExpect(status().isOk());
        capped(send("signup", address()));
        // Unknown resets get the same cap response as real accounts.
        capped(send("reset", address()));
        verify(mail, times(2)).send(anyString(), any(), anyString());
        assertThat(codes.findById(KcCodeEmailBudget.KEY).orElseThrow().getSendCount()).isEqualTo(2);
    }

    @Test void recreatedBudgetUsesPersistedCountAndOnlyResetsAtWindowEnd() throws Exception {
        send("signup", address()).andExpect(status().isOk());
        send("signup", address()).andExpect(status().isOk());
        Instant started = codes.findById(KcCodeEmailBudget.KEY).orElseThrow().getWindowStartedAt();
        var restarted = new KcCodeEmailBudget(codes, slots, transactions, 2,
                Clock.fixed(started.plusSeconds(3599), ZoneOffset.UTC));
        assertThatThrownBy(() -> restarted.reserve(true)).isInstanceOfSatisfying(KcAuthException.class, ex -> {
            assertThat(ex.getStatus()).isEqualTo(429);
            assertThat(ex.getMessage()).isEqualTo("unavailable");
        });
        var nextHour = new KcCodeEmailBudget(codes, slots, transactions, 2,
                Clock.fixed(started.plusSeconds(3600), ZoneOffset.UTC));
        nextHour.reserve(true);
        EmailCode persisted = codes.findById(KcCodeEmailBudget.KEY).orElseThrow();
        assertThat(persisted.getSendCount()).isEqualTo(1);
        assertThat(persisted.getWindowStartedAt()).isEqualTo(started.plusSeconds(3600));
    }

    @Test void smtpFailureDoesNotRefundTheDurableReservation() throws Exception {
        doThrow(new MailSendException("test transport failure")).when(mail).send(anyString(), any(), anyString());
        send("signup", address()).andExpect(status().isServiceUnavailable());
        send("signup", address()).andExpect(status().isServiceUnavailable());
        capped(send("signup", address()));
        assertThat(codes.findById(KcCodeEmailBudget.KEY).orElseThrow().getSendCount()).isEqualTo(2);
        verify(mail, times(2)).send(anyString(), any(), anyString());
    }

    @Test void missingAndInactiveResetsDoNotConsumeMailCapacity() throws Exception {
        send("reset", address()).andExpect(status().isOk());
        String inactive = address();
        users.saveAndFlush(User.builder().email(inactive).fullName("Budget test")
                .password("unused-test-hash").role(Role.STAFF).isActive(false).build());
        send("reset", inactive).andExpect(status().isOk());
        assertThat(codes.findById(KcCodeEmailBudget.KEY).orElseThrow().getSendCount()).isZero();
        verifyNoInteractions(mail);
    }

    @Test void concurrentDifferentAddressesCannotTakeTheSameLastSlot() throws Exception {
        budget.reserve(true);
        try (var pool = Executors.newFixedThreadPool(2)) {
            CountDownLatch start = new CountDownLatch(1);
            Callable<Boolean> call = () -> {
                start.await();
                try { accounts.sendCode("signup", address()); return true; }
                catch (KcAuthException ex) {
                    assertThat(ex.getStatus()).isEqualTo(429);
                    return false;
                }
            };
            var a = pool.submit(call); var b = pool.submit(call); start.countDown();
            assertThat(List.of(a.get(20, TimeUnit.SECONDS), b.get(20, TimeUnit.SECONDS)))
                    .containsExactlyInAnyOrder(true, false);
        }
        verify(mail, times(1)).send(anyString(), any(), anyString());
        assertThat(codes.findById(KcCodeEmailBudget.KEY).orElseThrow().getSendCount()).isEqualTo(2);
    }
}
