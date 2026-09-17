package com.raaspal.robotrecommendation.robotunit.service;

import com.raaspal.robotrecommendation.common.exception.ResourceNotFoundException;
import com.raaspal.robotrecommendation.robotunit.dto.ContractRenewalFollowup;
import com.raaspal.robotrecommendation.robotunit.entity.ContractRenewalStatus;
import com.raaspal.robotrecommendation.robotunit.entity.Deployment;
import com.raaspal.robotrecommendation.robotunit.repository.DeploymentRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * Recording what the customer success team has done about a contract renewal.
 *
 * <p>Addressed by robot unit, like the contract PDF, because that is what a Contracts
 * row is. One customer, one contract, one phone call: when asked, the same status is
 * written to every other active deployment of the customer with the same contract
 * dates, using the same lookup the PDF attach uses.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ContractRenewalFollowupService {

    private final DeploymentRepository deployments;

    /** What an update did: the follow-up as stored, and how many deployments carry it. */
    public record Updated(ContractRenewalFollowup followup, int deploymentsUpdated) {
    }

    /**
     * @param status              {@link ContractRenewalStatus#NOT_CONTACTED} clears the
     *                            follow-up (note and author too) - it is the unset state
     * @param applyToSameContract also write it to the other robots on the same contract
     */
    @Transactional
    public Updated update(UUID robotUnitId,
                          ContractRenewalStatus status,
                          String note,
                          boolean applyToSameContract,
                          String updatedBy) {
        Deployment target = activeDeployment(robotUnitId);

        List<Deployment> covered = new ArrayList<>();
        covered.add(target);
        if (applyToSameContract && target.getContractEndDate() != null) {
            UUID customerId = target.getCustomerProfile().getId();
            List<Deployment> sameContract = target.getContractStartDate() == null
                    ? deployments.findActiveOnSameContractWithoutStart(customerId, target.getContractEndDate())
                    : deployments.findActiveOnSameContract(customerId, target.getContractStartDate(), target.getContractEndDate());
            for (Deployment d : sameContract) {
                if (!d.getId().equals(target.getId())) covered.add(d);
            }
        }

        String cleanNote = note == null || note.isBlank() ? null : note.strip();
        Instant now = Instant.now();
        for (Deployment d : covered) {
            if (status == null || status == ContractRenewalStatus.NOT_CONTACTED) {
                d.clearRenewalFollowup();
            } else {
                d.setRenewalStatus(status);
                d.setRenewalNote(cleanNote);
                d.setRenewalUpdatedBy(updatedBy);
                d.setRenewalUpdatedAt(now);
            }
        }
        deployments.saveAll(covered);

        log.info("Contract renewal follow-up {} recorded on {} deployment(s) of customer {} by {}",
                status, covered.size(), target.getCustomerProfile().getId(), updatedBy);
        return new Updated(ContractRenewalFollowup.of(target), covered.size());
    }

    private Deployment activeDeployment(UUID robotUnitId) {
        List<Deployment> active = deployments.findByRobotUnitIdAndIsActiveTrue(robotUnitId);
        if (active.isEmpty()) {
            throw new ResourceNotFoundException("Robot " + robotUnitId + " has no active deployment.");
        }
        return active.get(0);
    }
}
