package com.raaspal.robotrecommendation.mkstock.service;

import com.raaspal.robotrecommendation.common.exception.BadRequestException;
import com.raaspal.robotrecommendation.mkstock.dto.MkDtos.AccessStatus;
import com.raaspal.robotrecommendation.mkstock.dto.MkDtos.ViewSession;
import com.raaspal.robotrecommendation.mkstock.entity.MkAccessPin;
import com.raaspal.robotrecommendation.mkstock.entity.MkViewSession;
import com.raaspal.robotrecommendation.mkstock.repository.MkAccessPinRepository;
import com.raaspal.robotrecommendation.mkstock.repository.MkViewSessionRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.time.Duration;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

/**
 * MK's way in: one shared PIN we set, exchanged for a view-only session.
 *
 * <p>The PIN is kept only as a BCrypt hash. A correct PIN returns a random token; only its
 * SHA-256 is stored, tied to the PIN it was issued for, so setting a new PIN (or turning
 * access off) ends every open MK session at once.
 *
 * <p>Guessing is slowed twice over: five wrong tries from one client lock that client out
 * for 15 minutes, and 30 wrong tries in 15 minutes from anywhere lock the PIN screen for
 * everyone for 15 minutes. With a 6-digit PIN that puts a blind search at years.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class MkAccessService {

    static final Duration SESSION_LENGTH = Duration.ofHours(12);
    static final Duration LOCKOUT = Duration.ofMinutes(15);
    static final int CLIENT_ATTEMPTS = 5;
    static final int GLOBAL_ATTEMPTS = 30;

    private final MkAccessPinRepository pins;
    private final MkViewSessionRepository sessions;
    private final PasswordEncoder passwordEncoder;

    private final SecureRandom random = new SecureRandom();
    /** client key -> failure times in the current window. */
    private final Map<String, Deque<Instant>> failures = new ConcurrentHashMap<>();
    private final Deque<Instant> allFailures = new ArrayDeque<>();

    /** Thrown for a wrong PIN, a lockout or a dead session - the controller answers 401/429. */
    public static class Denied extends RuntimeException {
        private final boolean tooMany;

        public Denied(String message, boolean tooMany) {
            super(message);
            this.tooMany = tooMany;
        }

        public boolean tooMany() {
            return tooMany;
        }
    }

    /* ─── Managed by RAAS PAL ────────────────────────────────────────────── */

    @Transactional(readOnly = true)
    public AccessStatus status() {
        Optional<MkAccessPin> pin = pins.findByActiveTrue();
        long live = pin.map(p -> sessions.countByPinIdAndExpiresAtAfter(p.getId(), Instant.now())).orElse(0L);
        return new AccessStatus(pin.isPresent(), pin.map(MkAccessPin::getCreatedAt).orElse(null),
                pin.map(MkAccessPin::getCreatedBy).orElse(null), pin.map(MkAccessPin::getLastUsedAt).orElse(null), live);
    }

    /** Sets a new PIN. The old one stops working and every MK session ends. */
    @Transactional
    public AccessStatus setPin(String pin, String actor) {
        if (pin == null || !pin.matches("\\d{6,12}")) throw new BadRequestException("The PIN must be 6 to 12 digits");
        if (pin.chars().distinct().count() == 1 || "0123456789".contains(pin) || "9876543210".contains(pin)) {
            throw new BadRequestException("That PIN is too easy to guess - avoid repeated or sequential digits");
        }
        retireActivePin();
        pins.saveAndFlush(MkAccessPin.builder().pinHash(passwordEncoder.encode(pin)).createdBy(actor).build());
        log.info("MK view PIN set by {}", actor);
        return status();
    }

    /** Turns MK access off: no PIN works until a new one is set. */
    @Transactional
    public AccessStatus disable(String actor) {
        retireActivePin();
        log.info("MK view access turned off by {}", actor);
        return status();
    }

    private void retireActivePin() {
        pins.findByActiveTrue().ifPresent(p -> {
            p.setActive(false);
            p.setRevokedAt(Instant.now());
            pins.saveAndFlush(p);
        });
        sessions.deleteAllSessions();
    }

    /* ─── MK's side ──────────────────────────────────────────────────────── */

    /** Checks the PIN and opens a view-only session. {@code clientKey} identifies the caller for lockout. */
    @Transactional
    public ViewSession login(String pin, String clientKey) {
        Instant now = Instant.now();
        String client = clientKey == null || clientKey.isBlank() ? "unknown" : clientKey;
        checkNotLocked(client, now);

        MkAccessPin active = pins.findByActiveTrue().orElse(null);
        if (active == null || pin == null || !passwordEncoder.matches(pin.trim(), active.getPinHash())) {
            recordFailure(client, now);
            throw new Denied("That PIN is not right", false);
        }
        failures.remove(client);

        String token = newToken();
        Instant expires = now.plus(SESSION_LENGTH);
        sessions.deleteExpired(now);
        sessions.save(MkViewSession.builder().tokenHash(sha256(token)).pinId(active.getId()).expiresAt(expires).build());
        active.setLastUsedAt(now);
        pins.save(active);
        return new ViewSession(token, expires);
    }

    /** Throws {@link Denied} unless the token belongs to a live session under the current PIN. */
    @Transactional(readOnly = true)
    public void requireSession(String token) {
        if (token == null || token.isBlank()) throw new Denied("Enter the PIN to view MK stock", false);
        MkViewSession s = sessions.findByTokenHash(sha256(token.trim())).orElse(null);
        MkAccessPin active = pins.findByActiveTrue().orElse(null);
        if (s == null || active == null || !s.getPinId().equals(active.getId()) || s.getExpiresAt().isBefore(Instant.now())) {
            throw new Denied("Your MK session has ended - enter the PIN again", false);
        }
    }

    @Transactional
    public void logout(String token) {
        if (token == null || token.isBlank()) return;
        sessions.findByTokenHash(sha256(token.trim())).ifPresent(sessions::delete);
    }

    /* ─── Lockout ────────────────────────────────────────────────────────── */

    private synchronized void checkNotLocked(String client, Instant now) {
        prune(allFailures, now);
        if (failures.size() > 1000) {   // keep the map from growing with one-off clients
            failures.values().forEach(d -> prune(d, now));
            failures.values().removeIf(Deque::isEmpty);
        }
        if (allFailures.size() >= GLOBAL_ATTEMPTS) {
            throw new Denied("Too many wrong PINs - try again in 15 minutes", true);
        }
        Deque<Instant> mine = failures.get(client);
        if (mine != null) {
            prune(mine, now);
            if (mine.size() >= CLIENT_ATTEMPTS) throw new Denied("Too many wrong PINs - try again in 15 minutes", true);
        }
    }

    private synchronized void recordFailure(String client, Instant now) {
        failures.computeIfAbsent(client, k -> new ArrayDeque<>()).addLast(now);
        allFailures.addLast(now);
        log.warn("Wrong MK view PIN from {}", client);
    }

    private static void prune(Deque<Instant> window, Instant now) {
        while (!window.isEmpty() && window.peekFirst().isBefore(now.minus(LOCKOUT))) window.pollFirst();
    }

    /* ─── Tokens ─────────────────────────────────────────────────────────── */

    private String newToken() {
        byte[] bytes = new byte[32];
        random.nextBytes(bytes);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }

    static String sha256(String value) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(digest);
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }
}
