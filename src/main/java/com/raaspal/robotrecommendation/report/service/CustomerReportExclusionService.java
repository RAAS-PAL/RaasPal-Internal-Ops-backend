package com.raaspal.robotrecommendation.report.service;

import com.raaspal.robotrecommendation.report.entity.CustomerReportExclusion;
import com.raaspal.robotrecommendation.report.repository.CustomerReportExclusionRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.Set;
import java.util.UUID;

/**
 * Which of a customer's robots are held back from one month's combined report.
 *
 * <p>The set is replaced wholesale rather than patched one robot at a time: the
 * UI presents a list of tickboxes and saves the resulting state, so "what is
 * ticked" is the whole intent. Patching would make an unticked box ambiguous
 * between "leave it in" and "no opinion".
 */
@Service
@RequiredArgsConstructor
public class CustomerReportExclusionService {

    private final CustomerReportExclusionRepository exclusionRepository;

    @Transactional(readOnly = true)
    public Set<UUID> get(UUID customerProfileId, String month) {
        return exclusionRepository.findAllByCustomerProfileIdAndReportMonth(customerProfileId, month).stream()
                .map(CustomerReportExclusion::getRobotUnitId)
                .collect(java.util.stream.Collectors.toCollection(LinkedHashSet::new));
    }

    /**
     * Replaces the held-back set for this customer+month.
     *
     * <p>Passing an empty collection clears every exclusion, which is how the UI
     * puts robots back into the report — the choice is always reversible, and
     * nothing about the robots' telemetry is touched either way.
     */
    @Transactional
    public Set<UUID> replace(UUID customerProfileId, String month, Collection<UUID> robotUnitIds) {
        exclusionRepository.deleteByCustomerProfileIdAndReportMonth(customerProfileId, month);
        // Flush the delete before inserting, or re-saving an id that was already
        // excluded collides with the unique constraint within the same transaction.
        exclusionRepository.flush();

        Set<UUID> unique = robotUnitIds == null ? Set.of() : new LinkedHashSet<>(robotUnitIds);
        if (!unique.isEmpty()) {
            exclusionRepository.saveAll(unique.stream()
                    .map(robotUnitId -> CustomerReportExclusion.builder()
                            .customerProfileId(customerProfileId)
                            .reportMonth(month)
                            .robotUnitId(robotUnitId)
                            .build())
                    .toList());
        }
        return unique;
    }
}
