package com.raaspal.robotrecommendation.partner.service;

import com.raaspal.robotrecommendation.common.exception.BadRequestException;
import com.raaspal.robotrecommendation.common.exception.ResourceNotFoundException;
import com.raaspal.robotrecommendation.partner.entity.Partner;
import com.raaspal.robotrecommendation.partner.entity.PartnerApiKey;
import com.raaspal.robotrecommendation.partner.repository.PartnerApiKeyRepository;
import com.raaspal.robotrecommendation.partner.repository.PartnerRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.time.LocalDateTime;
import java.util.Base64;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * The "locksmith" for partner API keys. It mints keys, and verifies keys shown
 * on incoming requests. Only a SHA-256 fingerprint of each key is stored — the
 * real key is shown once at creation and can never be recovered, so a database
 * leak never exposes usable keys.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class PartnerApiKeyService {

    private static final SecureRandom RANDOM = new SecureRandom();
    private static final String KEY_PREFIX = "pk_"; // partner key

    private final PartnerRepository partnerRepository;
    private final PartnerApiKeyRepository partnerApiKeyRepository;

    /** A newly minted key — the plaintext is returned ONCE and never stored. */
    public record GeneratedKey(UUID id, String apiKey, String keyPrefix, String label, LocalDateTime expiresAt) {
    }

    /**
     * A successfully authenticated caller: the key that matched plus the partner
     * it belongs to. Returned by {@link #authenticate(String)} to the auth filter.
     */
    public record AuthenticatedPartner(UUID keyId, UUID partnerId, String partnerName) {
    }

    /**
     * Mints a new API key for a partner and stores only its hash. The returned
     * {@code apiKey} is the caller's only chance to see the plaintext.
     *
     * @param expiresInDays optional lifetime; {@code null} mints a key that never
     *                      expires (rotation is then a manual mint-and-revoke)
     */
    @Transactional
    public GeneratedKey generate(UUID partnerId, String label, Integer expiresInDays) {
        Partner partner = partnerRepository.findById(partnerId)
                .orElseThrow(() -> new ResourceNotFoundException("Partner", "id", partnerId));

        if (expiresInDays != null && expiresInDays < 1) {
            throw new BadRequestException("expiresInDays must be at least 1");
        }

        String plaintext = KEY_PREFIX + randomToken();
        String prefix = plaintext.substring(0, 12); // display + non-security lookup aid
        LocalDateTime expiresAt = expiresInDays == null
                ? null
                : LocalDateTime.now().plusDays(expiresInDays);

        PartnerApiKey saved = partnerApiKeyRepository.save(PartnerApiKey.builder()
                .partnerId(partner.getId())
                .keyHash(sha256Hex(plaintext))
                .keyPrefix(prefix)
                .label(label)
                .isActive(true)
                .expiresAt(expiresAt)
                .build());

        log.info("Generated API key {} for partner {} ({}), expires {}",
                prefix, partner.getName(), partnerId, expiresAt == null ? "never" : expiresAt);
        return new GeneratedKey(saved.getId(), plaintext, prefix, label, expiresAt);
    }

    /** Mints a key that never expires. */
    @Transactional
    public GeneratedKey generate(UUID partnerId, String label) {
        return generate(partnerId, label, null);
    }

    /**
     * Resolves an incoming plaintext key to its usable record — active, not
     * revoked, and not expired. Lookup is an exact hash match (SHA-256 is
     * deterministic), so no candidate loop or timing-sensitive compare is needed.
     */
    @Transactional(readOnly = true)
    public Optional<PartnerApiKey> resolve(String plaintextKey) {
        if (plaintextKey == null || plaintextKey.isBlank()) {
            return Optional.empty();
        }
        return partnerApiKeyRepository.findByKeyHash(sha256Hex(plaintextKey))
                .filter(PartnerApiKey::isUsable);
    }

    /**
     * Full authentication of an incoming key: the key must be active and not
     * revoked <em>and</em> its owning partner must be active. Disabling a partner
     * therefore instantly rejects every one of its keys without touching the keys
     * themselves. Returns the matched key + partner, or empty if anything fails.
     */
    @Transactional(readOnly = true)
    public Optional<AuthenticatedPartner> authenticate(String plaintextKey) {
        return resolve(plaintextKey)
                .flatMap(key -> partnerRepository.findById(key.getPartnerId())
                        .filter(partner -> Boolean.TRUE.equals(partner.getIsActive()))
                        .map(partner -> new AuthenticatedPartner(
                                key.getId(), partner.getId(), partner.getName())));
    }

    /** All keys ever issued to a partner (active and revoked), newest first. */
    @Transactional(readOnly = true)
    public List<PartnerApiKey> listKeys(UUID partnerId) {
        if (!partnerRepository.existsById(partnerId)) {
            throw new ResourceNotFoundException("Partner", "id", partnerId);
        }
        return partnerApiKeyRepository.findByPartnerId(partnerId).stream()
                .sorted(Comparator.comparing(PartnerApiKey::getCreatedAt).reversed())
                .toList();
    }

    /** Records that a key was just used (best-effort; never blocks a request). */
    @Transactional
    public void touchLastUsed(UUID keyId) {
        partnerApiKeyRepository.findById(keyId).ifPresent(k -> {
            k.setLastUsedAt(LocalDateTime.now());
            partnerApiKeyRepository.save(k);
        });
    }

    /** Revokes a key immediately — future requests with it are rejected. */
    @Transactional
    public void revoke(UUID keyId) {
        PartnerApiKey key = partnerApiKeyRepository.findById(keyId)
                .orElseThrow(() -> new ResourceNotFoundException("PartnerApiKey", "id", keyId));
        key.setIsActive(false);
        key.setRevokedAt(LocalDateTime.now());
        partnerApiKeyRepository.save(key);
        log.info("Revoked API key {} ({})", key.getKeyPrefix(), keyId);
    }

    private String randomToken() {
        byte[] bytes = new byte[32];
        RANDOM.nextBytes(bytes);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }

    private String sha256Hex(String input) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256")
                    .digest(input.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(digest);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 unavailable", e); // never happens on a standard JVM
        }
    }
}
