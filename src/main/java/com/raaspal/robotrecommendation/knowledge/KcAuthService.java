package com.raaspal.robotrecommendation.knowledge;

import com.raaspal.robotrecommendation.auth.dto.AuthResponse;
import com.raaspal.robotrecommendation.auth.security.jwt.JwtUtils;
import com.raaspal.robotrecommendation.common.enums.Role;
import com.raaspal.robotrecommendation.user.dto.UserResponse;
import com.raaspal.robotrecommendation.user.entity.User;
import com.raaspal.robotrecommendation.user.repository.UserRepository;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.nio.charset.StandardCharsets;
import java.security.*;
import java.time.*;
import java.util.*;
import java.util.regex.Pattern;

@Service
// Wrong guesses MUST commit the attempt counter; they are expected business outcomes.
@Transactional(noRollbackFor = KcAuthException.class)
public class KcAuthService {
    private static final Pattern EMAIL = Pattern.compile("[a-z0-9._%+-]+@[a-z0-9.-]+\\.[a-z]{2,}");
    private static final SecureRandom RANDOM = new SecureRandom();
    private final EmailCodeRepository codes;
    private final EmailCodeSlot slots;
    private final UserRepository users;
    private final PasswordEncoder passwords;
    private final JwtUtils jwt;
    private final KcCodeMailer mail;
    private final Clock clock;

    @Autowired
    public KcAuthService(EmailCodeRepository codes, EmailCodeSlot slots, UserRepository users,
                         PasswordEncoder passwords, JwtUtils jwt, KcCodeMailer mail) {
        this(codes, slots, users, passwords, jwt, mail, Clock.systemUTC());
    }

    KcAuthService(EmailCodeRepository codes, EmailCodeSlot slots, UserRepository users,
                  PasswordEncoder passwords, JwtUtils jwt, KcCodeMailer mail, Clock clock) {
        this.codes = codes; this.slots = slots; this.users = users;
        this.passwords = passwords; this.jwt = jwt; this.mail = mail; this.clock = clock;
    }

    public static String companyEmail(String input) {
        String email = input == null ? "" : input.trim().toLowerCase(Locale.ROOT);
        if (!email.isEmpty() && !email.contains("@")) email += "@raaspal.com";
        if (email.length() > 255 || !EMAIL.matcher(email).matches()) throw new KcAuthException("invalid");
        if (!email.endsWith("@raaspal.com")) throw new KcAuthException("domain");
        return email;
    }

    private static EmailCode.Purpose purpose(String raw, String error) {
        try { return EmailCode.Purpose.valueOf(raw.toUpperCase(Locale.ROOT)); }
        catch (IllegalArgumentException | NullPointerException ex) { throw new KcAuthException(error); }
    }

    public String sendCode(String rawPurpose, String input) {
        EmailCode.Purpose purpose = purpose(rawPurpose, "invalid");
        String email = companyEmail(input);
        // Two concurrent inserts may race; the winner's row is then locked below.
        try { slots.ensure(email); }
        catch (DataIntegrityViolationException ex) {
            if (!codes.existsById(email)) throw ex;
        }
        EmailCode slot = codes.lockByEmail(email).orElseThrow();
        Instant now = clock.instant();
        if (slot.getCreatedAt() != null && now.isBefore(slot.getCreatedAt().plusSeconds(60))) {
            throw throttled(now, slot.getCreatedAt().plusSeconds(60));
        }
        if (slot.getWindowStartedAt() != null && now.isBefore(slot.getWindowStartedAt().plusSeconds(3600))
                && slot.getSendCount() >= 5) {
            throw throttled(now, slot.getWindowStartedAt().plusSeconds(3600));
        }
        Optional<User> user = users.findByEmailIgnoreCase(email);
        if (purpose == EmailCode.Purpose.SIGNUP && user.isPresent()) throw new KcAuthException("exists");

        String code = String.format(Locale.ROOT, "%06d", RANDOM.nextInt(1_000_000));
        slot.setPurpose(purpose);
        slot.setCodeHash(passwords.encode(code));
        slot.setCreatedAt(now);
        slot.setExpiresAt(now.plusSeconds(600));
        slot.setTries(0);
        slot.setUsedAt(null);
        slot.setTicketHash(null);
        slot.setTicketExpiresAt(null);
        slot.setTicketUsedAt(null);
        if (slot.getWindowStartedAt() == null || !now.isBefore(slot.getWindowStartedAt().plusSeconds(3600))) {
            slot.setWindowStartedAt(now);
            slot.setSendCount(0);
        }
        slot.setSendCount(slot.getSendCount() + 1);
        // Missing and disabled accounts get the same response and rate-limit state.
        // A transport exception rolls back the replacement; the previous code stays valid.
        if (purpose == EmailCode.Purpose.SIGNUP || user.filter(User::isActive).isPresent()) {
            mail.send(email, purpose, code);
        }
        return email;
    }

    private KcAuthException throttled(Instant now, Instant until) {
        return new KcAuthException("unavailable", 429, Math.max(1, Duration.between(now, until).toSeconds()));
    }

    public String verifyCode(String rawPurpose, String input, String code) {
        EmailCode.Purpose purpose = purpose(rawPurpose, "wrong");
        String email;
        try { email = companyEmail(input); }
        catch (KcAuthException ex) { throw new KcAuthException("wrong"); }
        EmailCode slot = codes.lockByEmail(email).orElseThrow(() -> new KcAuthException("expired"));
        Instant now = clock.instant();
        if (slot.getPurpose() != purpose || slot.getCodeHash() == null || slot.getUsedAt() != null
                || !now.isBefore(slot.getExpiresAt()) || slot.getTries() >= 5) {
            throw new KcAuthException("expired");
        }
        slot.setTries(slot.getTries() + 1);
        if (code == null || !code.matches("[0-9]{6}") || !passwords.matches(code, slot.getCodeHash())) {
            throw new KcAuthException("wrong");
        }
        byte[] secret = new byte[32];
        RANDOM.nextBytes(secret);
        String ticket = Base64.getUrlEncoder().withoutPadding().encodeToString(secret);
        slot.setUsedAt(now);
        slot.setTicketHash(hash(ticket));
        slot.setTicketExpiresAt(now.plusSeconds(600));
        return ticket;
    }

    public AuthResponse signUp(String ticket, String name, String password, String confirm) {
        String normalizedName = name == null ? "" : name.strip().replaceAll("\\s+", " ");
        if (normalizedName.isBlank() || normalizedName.length() > 255) throw new KcAuthException("name");
        validatePassword(password, confirm);
        EmailCode slot = ticket(ticket, EmailCode.Purpose.SIGNUP);
        if (users.existsByEmailIgnoreCase(slot.getEmail())) throw new KcAuthException("expired");
        User user = users.saveAndFlush(User.builder().email(slot.getEmail()).fullName(normalizedName)
                .password(passwords.encode(password)).role(Role.STAFF).kcRole(KcRole.VIEWER).build());
        slot.setTicketUsedAt(clock.instant());
        return session(user);
    }

    public AuthResponse reset(String ticket, String password, String confirm) {
        validatePassword(password, confirm);
        EmailCode slot = ticket(ticket, EmailCode.Purpose.RESET);
        User user = users.lockByEmail(slot.getEmail()).filter(User::isActive)
                .orElseThrow(() -> new KcAuthException("expired"));
        user.setPassword(passwords.encode(password));
        if (user.getKcRole() == null) user.setKcRole(KcRole.VIEWER);
        slot.setTicketUsedAt(clock.instant());
        return session(user);
    }

    private static void validatePassword(String password, String confirm) {
        if (password == null || password.length() < 8) throw new KcAuthException("short");
        // BCrypt accepts at most 72 UTF-8 bytes; never silently truncate a password.
        if (password.getBytes(StandardCharsets.UTF_8).length > 72) throw new KcAuthException("unavailable");
        if (confirm != null && !password.equals(confirm)) throw new KcAuthException("mismatch");
    }

    private EmailCode ticket(String ticket, EmailCode.Purpose purpose) {
        if (ticket == null || !ticket.matches("[A-Za-z0-9_-]{43}")) throw new KcAuthException("expired");
        EmailCode slot = codes.lockByTicket(hash(ticket)).orElseThrow(() -> new KcAuthException("expired"));
        if (slot.getPurpose() != purpose || slot.getTicketUsedAt() != null
                || !clock.instant().isBefore(slot.getTicketExpiresAt())) throw new KcAuthException("expired");
        companyEmail(slot.getEmail());
        return slot;
    }

    private AuthResponse session(User user) {
        return new AuthResponse(jwt.generateToken(user.getEmail()), UserResponse.from(user));
    }

    public record Me(String name, String email, KcRole kc_role) {}

    public Me me(String email) {
        try { companyEmail(email); }
        catch (KcAuthException ex) { throw new KcAuthException("domain", 403, 0); }
        User user = users.lockByEmail(email).filter(User::isActive)
                .orElseThrow(() -> new KcAuthException("credentials", 401, 0));
        if (user.getKcRole() == null) user.setKcRole(KcRole.VIEWER);
        return new Me(user.getFullName(), user.getEmail(), user.getKcRole());
    }

    static String hash(String ticket) {
        try { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(ticket.getBytes(StandardCharsets.UTF_8))); }
        catch (NoSuchAlgorithmException ex) { throw new IllegalStateException("SHA-256 unavailable"); }
    }
}
