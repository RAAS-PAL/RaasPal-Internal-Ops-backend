package com.raaspal.robotrecommendation.robotunit;

import com.raaspal.robotrecommendation.customer.entity.CustomerProfile;
import com.raaspal.robotrecommendation.customer.repository.CustomerProfileRepository;
import com.raaspal.robotrecommendation.robotunit.dto.ContractExpiryResponse;
import com.raaspal.robotrecommendation.robotunit.dto.ContractExpiryResponse.Status;
import com.raaspal.robotrecommendation.robotunit.dto.UpdateRobotRequest;
import com.raaspal.robotrecommendation.robotunit.entity.Deployment;
import com.raaspal.robotrecommendation.robotunit.entity.RobotUnit;
import com.raaspal.robotrecommendation.robotunit.repository.DeploymentRepository;
import com.raaspal.robotrecommendation.robotunit.repository.RobotUnitRepository;
import com.raaspal.robotrecommendation.robotunit.service.ContractExpiryService;
import com.raaspal.robotrecommendation.robotunit.service.RobotUnitService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Which contracts are ending, which have ended, and which get alerted — once.
 *
 * <p>Dates are relative to today so the test does not go stale: one contract ends in
 * 10 days, one in 45, one ended 5 days ago, one has no end date.
 */
@SpringBootTest
@Transactional
class ContractExpiryServiceTest {

    private static final ZoneId BANGKOK = ZoneId.of("Asia/Bangkok");

    @Autowired private ContractExpiryService service;
    @Autowired private RobotUnitService robotUnitService;
    @Autowired private CustomerProfileRepository customerProfileRepository;
    @Autowired private RobotUnitRepository robotUnitRepository;
    @Autowired private DeploymentRepository deploymentRepository;

    private CustomerProfile customer;
    private LocalDate today;
    private RobotUnit soon;
    private Deployment soonDeployment;

    @BeforeEach
    void setUp() {
        today = LocalDate.now(BANGKOK);
        customer = customerProfileRepository.save(CustomerProfile.builder().companyName("Renewals Co").build());

        soon = robot("GS-CX-SOON");
        soonDeployment = deploy(soon, today.plusDays(10));
        deploy(robot("GS-CX-LATER"), today.plusDays(45));
        deploy(robot("GS-CX-ENDED"), today.minusDays(5));
        deploy(robot("GS-CX-OPEN"), null);
    }

    @Test
    void listsEndingSoonAndEndedSeparately() {
        ContractExpiryResponse r = service.list(30);

        assertThat(r.endingSoon()).extracting(ContractExpiryResponse.Contract::serialNumber)
                .containsExactly("GS-CX-SOON");
        assertThat(r.endingSoon().get(0).daysToEnd()).isEqualTo(10);
        assertThat(r.endingSoon().get(0).status()).isEqualTo(Status.ENDING_SOON);

        assertThat(r.ended()).extracting(ContractExpiryResponse.Contract::serialNumber)
                .containsExactly("GS-CX-ENDED");
        assertThat(r.ended().get(0).daysToEnd()).isEqualTo(-5);
        assertThat(r.ended().get(0).status()).isEqualTo(Status.ENDED);
    }

    /** A wider window pulls the 45-day one in; the open contract never appears. */
    @Test
    void theWindowIsAParameter() {
        assertThat(service.list(60).endingSoon()).extracting(ContractExpiryResponse.Contract::serialNumber)
                .containsExactly("GS-CX-SOON", "GS-CX-LATER");
        assertThat(service.list(60).endingSoon()).extracting(ContractExpiryResponse.Contract::serialNumber)
                .doesNotContain("GS-CX-OPEN");
    }

    /** Alerted once: due, then stamped, then no longer due. */
    @Test
    void aContractIsDueForAlertOnceUntilItsEndDateChanges() {
        List<Deployment> due = service.dueForAlert(30);
        assertThat(due).extracting(d -> d.getRobotUnit().getSerialNumber()).containsExactly("GS-CX-SOON");

        service.markAlerted(due);
        assertThat(service.dueForAlert(30)).isEmpty();
        assertThat(service.list(30).endingSoon().get(0).alertedAt()).isNotNull();

        // Extending the contract re-arms the alert for the new end date.
        robotUnitService.update(soon.getId(), new UpdateRobotRequest(
                soon.getBrand(), soon.getModel(), soon.getName(), customer.getId(), "Site R",
                null, null, today.plusDays(20)));
        assertThat(service.dueForAlert(30)).extracting(d -> d.getRobotUnit().getSerialNumber())
                .containsExactly("GS-CX-SOON");

        // Saving the same end date again does not re-arm it.
        service.markAlerted(service.dueForAlert(30));
        robotUnitService.update(soon.getId(), new UpdateRobotRequest(
                soon.getBrand(), soon.getModel(), soon.getName(), customer.getId(), "Site R",
                null, null, today.plusDays(20)));
        assertThat(service.dueForAlert(30)).isEmpty();
    }

    /** The already-ended contract is not "due" — the moment for that alert has passed. */
    @Test
    void anEndedContractIsNotAlerted() {
        assertThat(service.dueForAlert(30)).extracting(d -> d.getRobotUnit().getSerialNumber())
                .doesNotContain("GS-CX-ENDED");
    }

    @Test
    void statusRule() {
        LocalDate t = LocalDate.of(2026, 9, 15);
        assertThat(ContractExpiryService.statusOf(null, t, 30)).isEqualTo(Status.NONE);
        assertThat(ContractExpiryService.statusOf(t.minusDays(1), t, 30)).isEqualTo(Status.ENDED);
        assertThat(ContractExpiryService.statusOf(t, t, 30)).isEqualTo(Status.ENDING_SOON);
        assertThat(ContractExpiryService.statusOf(t.plusDays(30), t, 30)).isEqualTo(Status.ENDING_SOON);
        assertThat(ContractExpiryService.statusOf(t.plusDays(31), t, 30)).isEqualTo(Status.ACTIVE);
    }

    private RobotUnit robot(String serial) {
        return robotUnitRepository.save(RobotUnit.builder()
                .serialNumber(serial).brand("Gausium").model("M50").name(serial).build());
    }

    private Deployment deploy(RobotUnit robot, LocalDate end) {
        return deploymentRepository.save(Deployment.builder()
                .robotUnit(robot).customerProfile(customer).site("Site R")
                .isActive(true).deployedAt(LocalDateTime.now())
                .contractEndDate(end).build());
    }
}
