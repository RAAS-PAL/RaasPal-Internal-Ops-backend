package com.raaspal.robotrecommendation.partner.repository;

import com.raaspal.robotrecommendation.partner.entity.PartnerApiKey;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface PartnerApiKeyRepository extends JpaRepository<PartnerApiKey, UUID> {

    /**
     * Candidate keys for an incoming request, narrowed by the key's prefix
     * (indexed). The caller then constant-time-compares the full hash.
     */
    List<PartnerApiKey> findByKeyPrefixAndIsActiveTrue(String keyPrefix);

    Optional<PartnerApiKey> findByKeyHash(String keyHash);

    List<PartnerApiKey> findByPartnerId(UUID partnerId);
}
