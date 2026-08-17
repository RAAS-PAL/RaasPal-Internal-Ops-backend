package com.raaspal.robotrecommendation.report;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.raaspal.robotrecommendation.customer.entity.CustomerProfile;
import com.raaspal.robotrecommendation.customer.repository.CustomerProfileRepository;
import com.raaspal.robotrecommendation.report.dto.CustomerBundlePreviewResponse;
import com.raaspal.robotrecommendation.report.service.CustomerReportBundleService;
import com.raaspal.robotrecommendation.report.service.CustomerReportExclusionService;
import com.raaspal.robotrecommendation.robotunit.entity.Deployment;
import com.raaspal.robotrecommendation.robotunit.entity.RobotUnit;
import com.raaspal.robotrecommendation.robotunit.repository.DeploymentRepository;
import com.raaspal.robotrecommendation.robotunit.repository.RobotUnitRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Curating a customer's combined monthly report.
 *
 * <p>A customer like IFS has many robots and some are offline in any given month.
 * Customer success reviews the whole bundle and holds back the robots with no
 * activity before sending. The contract these tests pin is that the staff preview
 * and the customer's public link can never disagree about what was sent.
 */
@SpringBootTest
@AutoConfigureMockMvc
@Transactional
@WithMockUser
class CustomerBundleExclusionTest {

    private static final String MONTH = "2026-07";
    private static final String PREVIEW = "/api/v1/reports/customer-bundle/preview";
    private static final String EXCLUSIONS = "/api/v1/reports/customer-bundle/exclusions";

    @Autowired private MockMvc mockMvc;
    @Autowired private ObjectMapper objectMapper;
    @Autowired private CustomerProfileRepository customerProfileRepository;
    @Autowired private RobotUnitRepository robotUnitRepository;
    @Autowired private DeploymentRepository deploymentRepository;
    @Autowired private CustomerReportBundleService bundleService;
    @Autowired private CustomerReportExclusionService exclusionService;

    private CustomerProfile customer;
    private RobotUnit robotA;
    private RobotUnit robotB;

    @BeforeEach
    void setUp() {
        customer = customerProfileRepository.save(
                CustomerProfile.builder().companyName("IFS Test").build());
        robotA = deployRobot("GS-TEST-A", "Tower A");
        robotB = deployRobot("GS-TEST-B", "Tower B");
    }

    private RobotUnit deployRobot(String serialNumber, String site) {
        RobotUnit unit = robotUnitRepository.save(RobotUnit.builder()
                .serialNumber(serialNumber)
                .brand("Gausium")
                .model("M50")
                .name(serialNumber)
                .build());
        deploymentRepository.save(Deployment.builder()
                .robotUnit(unit)
                .customerProfile(customer)
                .site(site)
                .isActive(true)
                .deployedAt(LocalDateTime.now())
                .build());
        return unit;
    }

    private void setExclusions(UUID... robotUnitIds) throws Exception {
        mockMvc.perform(put(EXCLUSIONS)
                        .param("customerProfileId", customer.getId().toString())
                        .param("month", MONTH)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(
                                Map.of("excludedRobotUnitIds", List.of(robotUnitIds)))))
                .andExpect(status().isOk());
    }

    /** Staff need to see every robot, including held-back ones, to curate at all. */
    @Test
    void thePreviewListsEveryDeployedRobotWithItsActivityAndExclusionState() throws Exception {
        mockMvc.perform(get(PREVIEW)
                        .param("customerProfileId", customer.getId().toString())
                        .param("month", MONTH))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.customerName").value("IFS Test"))
                .andExpect(jsonPath("$.data.robots.length()").value(2))
                .andExpect(jsonPath("$.data.includedCount").value(2))
                // No task reports were synced, so neither robot has activity — this is
                // exactly the offline case the feature exists for.
                .andExpect(jsonPath("$.data.robots[0].hasData").value(false))
                .andExpect(jsonPath("$.data.robots[0].excluded").value(false));
    }

    /**
     * The whole point: an excluded robot must vanish from what the customer opens,
     * not merely from the staff view.
     */
    @Test
    void anExcludedRobotIsDroppedFromTheCustomerFacingBundle() throws Exception {
        assertThat(bundleService.build(customer.getId(), MONTH).robots()).hasSize(2);

        setExclusions(robotA.getId());

        assertThat(bundleService.build(customer.getId(), MONTH).robots())
                .as("the customer's link must show only the robot that was kept")
                .hasSize(1)
                .allSatisfy(r -> assertThat(r.serialNumber()).isEqualTo("GS-TEST-B"));
    }

    /**
     * Un-ticking a robot puts it back. The exclusion is a filter, never a deletion,
     * so nothing is lost by holding a robot back and changing your mind.
     */
    @Test
    void clearingAnExclusionPutsTheRobotBackIntoTheReport() throws Exception {
        setExclusions(robotA.getId());
        assertThat(bundleService.build(customer.getId(), MONTH).robots()).hasSize(1);

        setExclusions(); // empty list = include everything again

        assertThat(bundleService.build(customer.getId(), MONTH).robots())
                .as("clearing exclusions must restore every robot")
                .hasSize(2);
        assertThat(bundleService.buildPreview(customer.getId(), MONTH).robots())
                .allSatisfy(r -> assertThat(r.excluded()).isFalse());
    }

    /**
     * A robot idle in July is usually back in service in August. If an exclusion
     * carried forward it would quietly drop the robot from every future report.
     */
    @Test
    void anExclusionAppliesOnlyToTheMonthItWasSetFor() throws Exception {
        setExclusions(robotA.getId());

        assertThat(bundleService.build(customer.getId(), MONTH).robots()).hasSize(1);
        assertThat(bundleService.build(customer.getId(), "2026-08").robots())
                .as("August must be unaffected by a July exclusion")
                .hasSize(2);
    }

    /** Saving the same selection twice must not collide on the unique constraint. */
    @Test
    void savingTheSameExclusionTwiceIsIdempotent() throws Exception {
        setExclusions(robotA.getId());
        setExclusions(robotA.getId());

        assertThat(exclusionService.get(customer.getId(), MONTH)).isEqualTo(Set.of(robotA.getId()));
    }

    /** The preview reports what would be sent, so the UI can warn before sending nothing. */
    @Test
    void excludingEveryRobotLeavesAnIncludedCountOfZero() throws Exception {
        setExclusions(robotA.getId(), robotB.getId());

        CustomerBundlePreviewResponse preview = bundleService.buildPreview(customer.getId(), MONTH);
        assertThat(preview.includedCount()).isZero();
        assertThat(preview.robots()).hasSize(2).allSatisfy(r -> assertThat(r.excluded()).isTrue());
        assertThat(bundleService.build(customer.getId(), MONTH).robots()).isEmpty();
    }
}
