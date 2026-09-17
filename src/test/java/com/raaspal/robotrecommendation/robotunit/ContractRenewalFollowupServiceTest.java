package com.raaspal.robotrecommendation.robotunit;

import com.raaspal.robotrecommendation.common.exception.ResourceNotFoundException;
import com.raaspal.robotrecommendation.customer.entity.CustomerProfile;
import com.raaspal.robotrecommendation.robotunit.dto.ContractRenewalFollowup;
import com.raaspal.robotrecommendation.robotunit.entity.ContractRenewalStatus;
import com.raaspal.robotrecommendation.robotunit.entity.Deployment;
import com.raaspal.robotrecommendation.robotunit.entity.RobotUnit;
import com.raaspal.robotrecommendation.robotunit.repository.DeploymentRepository;
import com.raaspal.robotrecommendation.robotunit.service.ContractRenewalFollowupService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * One phone call, several robots: what recording a renewal follow-up does to the
 * deployments on the same contract, and how a term starts over.
 */
class ContractRenewalFollowupServiceTest {

    private final DeploymentRepository deployments = mock(DeploymentRepository.class);
    private final ContractRenewalFollowupService service = new ContractRenewalFollowupService(deployments);

    private final CustomerProfile ifs = CustomerProfile.builder().id(UUID.randomUUID()).companyName("IFS").build();
    private final Deployment siamCenter = deployment(ifs, "2025-09-30", "2026-09-29");
    private final Deployment siamParagon = deployment(ifs, "2025-09-30", "2026-09-29");
    private final Deployment otherDates = deployment(ifs, "2026-01-01", "2026-12-31");
    private final Deployment openEnded = deployment(ifs, "2026-01-01", null);

    @BeforeEach
    void wire() {
        for (Deployment d : List.of(siamCenter, siamParagon, otherDates, openEnded)) {
            when(deployments.findByRobotUnitIdAndIsActiveTrue(d.getRobotUnit().getId())).thenReturn(List.of(d));
        }
        when(deployments.findActiveOnSameContract(ifs.getId(), LocalDate.parse("2025-09-30"), LocalDate.parse("2026-09-29")))
                .thenReturn(List.of(siamCenter, siamParagon));
    }

    @Test
    void oneCallCoversEveryRobotOnTheContractWhenAsked() {
        ContractRenewalFollowupService.Updated updated = service.update(
                siamCenter.getRobotUnit().getId(), ContractRenewalStatus.CONTACTED, "  Khun A will confirm Friday  ", true, "branny");

        assertThat(updated.deploymentsUpdated()).isEqualTo(2);
        assertThat(updated.followup().status()).isEqualTo(ContractRenewalStatus.CONTACTED);
        assertThat(updated.followup().note()).isEqualTo("Khun A will confirm Friday");
        assertThat(updated.followup().updatedBy()).isEqualTo("branny");
        assertThat(updated.followup().updatedAt()).isNotNull();
        assertThat(siamParagon.getRenewalStatus()).isEqualTo(ContractRenewalStatus.CONTACTED);
        assertThat(siamParagon.getRenewalNote()).isEqualTo("Khun A will confirm Friday");
        assertThat(otherDates.getRenewalStatus()).isNull();
    }

    @Test
    void thisRobotOnlyLeavesTheOthersAlone() {
        service.update(siamCenter.getRobotUnit().getId(), ContractRenewalStatus.WILL_RENEW, null, false, "branny");

        assertThat(siamCenter.getRenewalStatus()).isEqualTo(ContractRenewalStatus.WILL_RENEW);
        assertThat(siamCenter.getRenewalNote()).isNull();
        assertThat(siamParagon.getRenewalStatus()).isNull();
    }

    /** No end date means no contract to share, so only the one robot is touched. */
    @Test
    void anOpenEndedDeploymentIsUpdatedAlone() {
        ContractRenewalFollowupService.Updated updated = service.update(
                openEnded.getRobotUnit().getId(), ContractRenewalStatus.CONTACTED, "note", true, "b");
        assertThat(updated.deploymentsUpdated()).isEqualTo(1);
        assertThat(openEnded.getRenewalStatus()).isEqualTo(ContractRenewalStatus.CONTACTED);
    }

    /** "Not contacted" with no note is the unset state: it wipes the author too. */
    @Test
    void notContactedWithoutANoteClearsEverything() {
        service.update(siamCenter.getRobotUnit().getId(), ContractRenewalStatus.WILL_NOT_RENEW, "moving to another vendor", false, "b");
        service.update(siamCenter.getRobotUnit().getId(), ContractRenewalStatus.NOT_CONTACTED, "  ", false, "b");

        assertThat(siamCenter.getRenewalStatus()).isNull();
        assertThat(siamCenter.getRenewalNote()).isNull();
        assertThat(siamCenter.getRenewalUpdatedBy()).isNull();
        assertThat(ContractRenewalFollowup.of(siamCenter).status()).isEqualTo(ContractRenewalStatus.NOT_CONTACTED);
    }

    /** A reminder to oneself needs no call first. */
    @Test
    void aNoteMayStandWithoutAStatus() {
        service.update(siamCenter.getRobotUnit().getId(), ContractRenewalStatus.NOT_CONTACTED, "call back after the 20th", false, "b");

        assertThat(siamCenter.getRenewalStatus()).isNull();
        ContractRenewalFollowup f = ContractRenewalFollowup.of(siamCenter);
        assertThat(f.status()).isEqualTo(ContractRenewalStatus.NOT_CONTACTED);
        assertThat(f.note()).isEqualTo("call back after the 20th");
        assertThat(f.updatedBy()).isEqualTo("b");
    }

    /** A new end date is a new term; the follow-up starts over with it. */
    @Test
    void aNewTermStartsAsNotContacted() {
        service.update(siamCenter.getRobotUnit().getId(), ContractRenewalStatus.WILL_RENEW, "signed", false, "b");
        siamCenter.clearRenewalFollowup();
        assertThat(ContractRenewalFollowup.of(siamCenter))
                .isEqualTo(new ContractRenewalFollowup(ContractRenewalStatus.NOT_CONTACTED, null, null, null));
    }

    @Test
    void aRobotWithoutAnActiveDeploymentIsRefused() {
        assertThatThrownBy(() -> service.update(UUID.randomUUID(), ContractRenewalStatus.CONTACTED, null, true, "b"))
                .isInstanceOf(ResourceNotFoundException.class);
    }

    private static Deployment deployment(CustomerProfile customer, String start, String end) {
        return Deployment.builder()
                .id(UUID.randomUUID())
                .robotUnit(RobotUnit.builder()
                        .id(UUID.randomUUID()).serialNumber("GS-" + UUID.randomUUID().toString().substring(0, 6)).build())
                .customerProfile(customer)
                .isActive(true)
                .contractStartDate(LocalDate.parse(start))
                .contractEndDate(end == null ? null : LocalDate.parse(end))
                .build();
    }
}
