package com.raaspal.robotrecommendation.partner.service;

import com.raaspal.robotrecommendation.common.exception.BadRequestException;
import com.raaspal.robotrecommendation.common.exception.ResourceNotFoundException;
import com.raaspal.robotrecommendation.partner.dto.PartnerResponse;
import com.raaspal.robotrecommendation.partner.dto.UpdatePartnerRequest;
import com.raaspal.robotrecommendation.partner.entity.Partner;
import com.raaspal.robotrecommendation.partner.repository.PartnerRepository;
import com.raaspal.robotrecommendation.robotunit.entity.Deployment;
import com.raaspal.robotrecommendation.robotunit.repository.DeploymentRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Comparator;
import java.util.List;
import java.util.UUID;

/**
 * Admin-side management of distributor / service partners and which deployments
 * each one services. Key minting lives in {@link PartnerApiKeyService}; this
 * service owns the partner record and the {@code deployments.partner_id} link
 * that scopes every partner-API read.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class PartnerService {

    private final PartnerRepository partnerRepository;
    private final DeploymentRepository deploymentRepository;

    /** Registers a new partner. Names are unique (case-insensitive). */
    @Transactional
    public PartnerResponse create(String name) {
        String trimmed = name.trim();
        if (partnerRepository.existsByNameIgnoreCase(trimmed)) {
            throw new BadRequestException("A partner named '" + trimmed + "' already exists");
        }
        Partner saved = partnerRepository.save(Partner.builder()
                .name(trimmed)
                .isActive(true)
                .build());
        log.info("Created partner {} ({})", saved.getName(), saved.getId());
        return PartnerResponse.of(saved);
    }

    /** All partners, newest first. */
    @Transactional(readOnly = true)
    public List<PartnerResponse> list() {
        return partnerRepository.findAll().stream()
                .sorted(Comparator.comparing(Partner::getCreatedAt).reversed())
                .map(PartnerResponse::of)
                .toList();
    }

    /**
     * Renames and/or enables/disables a partner. Disabling ({@code active=false})
     * is an instant kill-switch: the partner auth filter rejects every key of an
     * inactive partner, so no re-issuing of keys is needed to cut off access.
     */
    @Transactional
    public PartnerResponse update(UUID partnerId, UpdatePartnerRequest request) {
        Partner partner = partnerRepository.findById(partnerId)
                .orElseThrow(() -> new ResourceNotFoundException("Partner", "id", partnerId));

        if (request.name() != null && !request.name().isBlank()) {
            String trimmed = request.name().trim();
            if (!trimmed.equalsIgnoreCase(partner.getName())
                    && partnerRepository.existsByNameIgnoreCase(trimmed)) {
                throw new BadRequestException("A partner named '" + trimmed + "' already exists");
            }
            partner.setName(trimmed);
        }
        if (request.active() != null) {
            partner.setIsActive(request.active());
            log.info("Partner {} ({}) is now {}", partner.getName(), partnerId,
                    request.active() ? "ACTIVE" : "DISABLED");
        }
        return PartnerResponse.of(partnerRepository.save(partner));
    }

    /**
     * Assigns a deployment to the partner that services it, or un-assigns it
     * ({@code partnerId == null} → RAASPAL-direct). This link is the sole scope
     * of a partner's data access.
     */
    @Transactional
    public void assignDeployment(UUID deploymentId, UUID partnerId) {
        Deployment deployment = deploymentRepository.findById(deploymentId)
                .orElseThrow(() -> new ResourceNotFoundException("Deployment", "id", deploymentId));

        if (partnerId != null && !partnerRepository.existsById(partnerId)) {
            throw new ResourceNotFoundException("Partner", "id", partnerId);
        }
        deployment.setPartnerId(partnerId);
        deploymentRepository.save(deployment);
        log.info("Deployment {} assigned to partner {}", deploymentId, partnerId);
    }
}
