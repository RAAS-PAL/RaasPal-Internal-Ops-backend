package com.raaspal.robotrecommendation.knowledge;

import com.raaspal.robotrecommendation.auth.security.jwt.JwtUtils;
import com.raaspal.robotrecommendation.user.repository.UserRepository;
import org.junit.jupiter.api.*;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.security.crypto.password.PasswordEncoder;
import java.time.*;
import java.util.Optional;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

class KcAuthServiceTest {
    static final String EMAIL = "kc-unit@raaspal.com";
    static final Instant NOW = Instant.parse("2026-10-06T00:00:00Z");
    final EmailCodeRepository codes = mock(EmailCodeRepository.class);
    final EmailCodeSlot slots = mock(EmailCodeSlot.class);
    final UserRepository users = mock(UserRepository.class);
    final PasswordEncoder passwords = mock(PasswordEncoder.class);
    final JwtUtils jwt = mock(JwtUtils.class);
    final KcCodeMailer mail = mock(KcCodeMailer.class);
    final KcAuthService service = new KcAuthService(codes, slots, users, passwords, jwt, mail,
            Clock.fixed(NOW, ZoneOffset.UTC));

    void fails(String code, org.assertj.core.api.ThrowableAssert.ThrowingCallable call) {
        assertThatThrownBy(call).isInstanceOf(KcAuthException.class).hasMessage(code);
    }

    @Test void localPartAndCaseNormalizeWithRootLocale() {
        assertThat(KcAuthService.companyEmail("  KC.Unit  ")).isEqualTo("kc.unit@raaspal.com");
        assertThat(KcAuthService.companyEmail("KC.Unit@RAASPAL.COM")).isEqualTo("kc.unit@raaspal.com");
    }

    @ParameterizedTest @ValueSource(strings = {"", "@raaspal.com", "a@@raaspal.com", "a b", "a@", "a\r\nb@raaspal.com"})
    void malformedEmail(String email) { fails("invalid", () -> KcAuthService.companyEmail(email)); }

    @ParameterizedTest @ValueSource(strings = {"a@example.org", "a@raaspal.com.evil.org", "a@sub.raaspal.com"})
    void wrongDomain(String email) { fails("domain", () -> KcAuthService.companyEmail(email)); }

    @Test void nullEmail() { fails("invalid", () -> KcAuthService.companyEmail(null)); }

    @Test void codeExpiresAtExactBoundary() {
        EmailCode slot = new EmailCode();
        slot.setPurpose(EmailCode.Purpose.SIGNUP); slot.setCodeHash("hash"); slot.setExpiresAt(NOW);
        when(codes.lockByEmail(EMAIL)).thenReturn(Optional.of(slot));
        fails("expired", () -> service.verifyCode("signup", EMAIL, "123456"));
        verifyNoInteractions(passwords);
    }

    @Test void ticketExpiresAtExactBoundary() {
        String ticket = "a".repeat(43);
        EmailCode slot = new EmailCode();
        slot.setPurpose(EmailCode.Purpose.SIGNUP); slot.setTicketExpiresAt(NOW);
        when(codes.lockByTicket(KcAuthService.hash(ticket))).thenReturn(Optional.of(slot));
        fails("expired", () -> service.signUp(ticket, "Test employee", "test-password", null));
        verifyNoInteractions(users);
    }

    @Test void nameAndPasswordValidationPrecedesTicketLookup() {
        fails("name", () -> service.signUp(null, null, "test-password", null));
        fails("name", () -> service.signUp(null, "x".repeat(256), "test-password", null));
        fails("short", () -> service.reset(null, null, null));
        fails("short", () -> service.reset(null, "1234567", null));
        fails("mismatch", () -> service.reset(null, "test-password", "different"));
        fails("unavailable", () -> service.reset(null, "ก".repeat(25), null));
        verifyNoInteractions(codes);
    }

    @Test void cooldownBoundaryAllowsSendAndHourlyBoundaryRestartsBudget() {
        EmailCode slot = new EmailCode();
        slot.setEmail(EMAIL); slot.setPurpose(EmailCode.Purpose.SIGNUP);
        slot.setCreatedAt(NOW.minusSeconds(60)); slot.setWindowStartedAt(NOW.minusSeconds(3600)); slot.setSendCount(5);
        when(codes.lockByEmail(EMAIL)).thenReturn(Optional.of(slot));
        when(users.findByEmailIgnoreCase(EMAIL)).thenReturn(Optional.empty());
        when(passwords.encode(anyString())).thenReturn("hashed");
        assertThat(service.sendCode("signup", EMAIL)).isEqualTo(EMAIL);
        assertThat(slot.getSendCount()).isEqualTo(1);
        assertThat(slot.getExpiresAt()).isEqualTo(NOW.plusSeconds(600));
        verify(mail).send(eq(EMAIL), eq(EmailCode.Purpose.SIGNUP), matches("[0-9]{6}"));
    }

    @Test void exhaustedCodeCannotCheckPasswordAgain() {
        EmailCode slot = new EmailCode();
        slot.setPurpose(EmailCode.Purpose.SIGNUP); slot.setCodeHash("hash");
        slot.setExpiresAt(NOW.plusSeconds(600)); slot.setTries(5);
        when(codes.lockByEmail(EMAIL)).thenReturn(Optional.of(slot));
        fails("expired", () -> service.verifyCode("signup", EMAIL, "123456"));
        verifyNoInteractions(passwords);
    }

    @Test void incorrectSixDigitCodeConsumesAnAttemptAndCannotIssueATicket() {
        EmailCode slot = new EmailCode();
        slot.setPurpose(EmailCode.Purpose.SIGNUP); slot.setCodeHash("hash");
        slot.setExpiresAt(NOW.plusSeconds(600));
        when(codes.lockByEmail(EMAIL)).thenReturn(Optional.of(slot));
        when(passwords.matches("123456", "hash")).thenReturn(false);
        fails("wrong", () -> service.verifyCode("signup", EMAIL, "123456"));
        assertThat(slot.getTries()).isEqualTo(1);
        assertThat(slot.getTicketHash()).isNull();
        assertThat(slot.getUsedAt()).isNull();
    }
}
