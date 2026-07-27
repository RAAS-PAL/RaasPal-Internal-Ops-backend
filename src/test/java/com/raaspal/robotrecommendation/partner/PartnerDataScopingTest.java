package com.raaspal.robotrecommendation.partner;

import com.raaspal.robotrecommendation.customer.entity.CustomerProfile;
import com.raaspal.robotrecommendation.customer.repository.CustomerProfileRepository;
import com.raaspal.robotrecommendation.partner.entity.Partner;
import com.raaspal.robotrecommendation.partner.repository.PartnerRepository;
import com.raaspal.robotrecommendation.partner.service.PartnerApiKeyService;
import com.raaspal.robotrecommendation.robotunit.entity.Deployment;
import com.raaspal.robotrecommendation.robotunit.entity.RobotUnit;
import com.raaspal.robotrecommendation.robotunit.repository.DeploymentRepository;
import com.raaspal.robotrecommendation.robotunit.repository.RobotUnitRepository;
import com.raaspal.robotrecommendation.telemetry.entity.RobotTaskReport;
import com.raaspal.robotrecommendation.telemetry.repository.RobotTaskReportRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.time.LocalDateTime;
import java.util.UUID;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * The core multi-tenancy guarantee: a partner sees exactly the robots assigned
 * to it through {@code deployments.partner_id}, and nothing else. Also pins the
 * filters and the page-size cap on the task-report endpoint.
 */
@SpringBootTest
@AutoConfigureMockMvc
@Transactional
class PartnerDataScopingTest {

    private static final String HEADER = "X-API-Key";
    private static final String ROBOTS = "/api/partner/v1/robots";

    @Autowired
    private MockMvc mockMvc;
    @Autowired
    private PartnerRepository partnerRepository;
    @Autowired
    private PartnerApiKeyService partnerApiKeyService;
    @Autowired
    private CustomerProfileRepository customerProfileRepository;
    @Autowired
    private RobotUnitRepository robotUnitRepository;
    @Autowired
    private DeploymentRepository deploymentRepository;
    @Autowired
    private RobotTaskReportRepository taskReportRepository;

    /** Unique per run so rows never collide with another test's fixtures. */
    private final String tag = UUID.randomUUID().toString().substring(0, 8);

    private String keyOfA;
    private String ownSerial;
    private String otherPartnersSerial;
    private String unassignedSerial;

    @BeforeEach
    void setUp() {
        Partner partnerA = partnerRepository.save(
                Partner.builder().name("Partner A " + tag).isActive(true).build());
        Partner partnerB = partnerRepository.save(
                Partner.builder().name("Partner B " + tag).isActive(true).build());
        keyOfA = partnerApiKeyService.generate(partnerA.getId(), "scoping test").apiKey();

        CustomerProfile customerOfA = customer("Customer A " + tag);
        CustomerProfile customerOfB = customer("Customer B " + tag);

        ownSerial = "SN-OWN-" + tag;
        otherPartnersSerial = "SN-OTHER-" + tag;
        unassignedSerial = "SN-NONE-" + tag;

        RobotUnit own = deployedRobot(ownSerial, "Own Robot", customerOfA, partnerA.getId());
        deployedRobot(otherPartnersSerial, "Other Robot", customerOfB, partnerB.getId());
        deployedRobot(unassignedSerial, "Direct Robot", customerOfA, null);

        // Two tasks in July, one in June — enough to prove the month/day filters.
        taskReport(own, customerOfA, "task-jul-15-" + tag, Instant.parse("2026-07-15T05:00:00Z"), "2026-07");
        taskReport(own, customerOfA, "task-jul-20-" + tag, Instant.parse("2026-07-20T05:00:00Z"), "2026-07");
        taskReport(own, customerOfA, "task-jun-10-" + tag, Instant.parse("2026-06-10T05:00:00Z"), "2026-06");
    }

    @Test
    void robotsListReturnsOnlyThisPartnersRobots() throws Exception {
        mockMvc.perform(get(ROBOTS).header(HEADER, keyOfA))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data[?(@.serialNumber == '" + ownSerial + "')]").exists())
                // Another partner's robot and a RAASPAL-direct robot must not appear.
                .andExpect(jsonPath("$.data[?(@.serialNumber == '" + otherPartnersSerial + "')]").doesNotExist())
                .andExpect(jsonPath("$.data[?(@.serialNumber == '" + unassignedSerial + "')]").doesNotExist());
    }

    @Test
    void taskReportsAreReadableForAnOwnedRobot() throws Exception {
        mockMvc.perform(get(ROBOTS + "/" + ownSerial + "/task-reports").header(HEADER, keyOfA))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.totalElements").value(3))
                .andExpect(jsonPath("$.data.content[0].robotSerialNumber").value(ownSerial));
    }

    /** Another partner's robot must be indistinguishable from one that does not exist. */
    @Test
    void anotherPartnersRobotIsNotFound() throws Exception {
        mockMvc.perform(get(ROBOTS + "/" + otherPartnersSerial + "/task-reports").header(HEADER, keyOfA))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.message")
                        .value("No robot with serial number '" + otherPartnersSerial + "' is available to this partner"));
    }

    @Test
    void unknownRobotIsNotFoundWithTheSameMessageShape() throws Exception {
        String missing = "SN-DOES-NOT-EXIST-" + tag;
        mockMvc.perform(get(ROBOTS + "/" + missing + "/task-reports").header(HEADER, keyOfA))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.message")
                        .value("No robot with serial number '" + missing + "' is available to this partner"));
    }

    @Test
    void aPartnerCannotReachAnotherPartnersRobotEvenWhenItIsDeployed() throws Exception {
        mockMvc.perform(get(ROBOTS + "/" + unassignedSerial + "/task-reports").header(HEADER, keyOfA))
                .andExpect(status().isNotFound());
    }

    @Test
    void monthFilterSelectsThatMonthOnly() throws Exception {
        mockMvc.perform(get(ROBOTS + "/" + ownSerial + "/task-reports")
                        .param("month", "2026-06")
                        .header(HEADER, keyOfA))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.totalElements").value(1));
    }

    @Test
    void singleDayFilterSelectsThatDayOnly() throws Exception {
        mockMvc.perform(get(ROBOTS + "/" + ownSerial + "/task-reports")
                        .param("from", "2026-07-15")
                        .header(HEADER, keyOfA))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.totalElements").value(1));
    }

    @Test
    void dateRangeFilterTakesPrecedenceOverMonth() throws Exception {
        mockMvc.perform(get(ROBOTS + "/" + ownSerial + "/task-reports")
                        .param("from", "2026-07-01")
                        .param("to", "2026-07-31")
                        .param("month", "2026-06") // ignored when from/to are present
                        .header(HEADER, keyOfA))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.totalElements").value(2));
    }

    @Test
    void invalidDateIsRejected() throws Exception {
        mockMvc.perform(get(ROBOTS + "/" + ownSerial + "/task-reports")
                        .param("from", "15-07-2026")
                        .header(HEADER, keyOfA))
                .andExpect(status().isBadRequest());
    }

    /* ── startTimeMin / startTimeMax (Gausium's naming, date-only) ─────────── */

    @Test
    void startTimeMinAndMaxSelectTheRange() throws Exception {
        mockMvc.perform(get(ROBOTS + "/" + ownSerial + "/task-reports")
                        .param("startTimeMin", "2026-07-01")
                        .param("startTimeMax", "2026-07-31")
                        .header(HEADER, keyOfA))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.totalElements").value(2));
    }

    @Test
    void aLoneStartTimeMinSelectsThatDay() throws Exception {
        mockMvc.perform(get(ROBOTS + "/" + ownSerial + "/task-reports")
                        .param("startTimeMin", "2026-07-15")
                        .header(HEADER, keyOfA))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.totalElements").value(1));
    }

    /** A datetime (what Gausium's own API takes) must be rejected, not truncated. */
    @Test
    void startTimeWithATimeComponentIsRejected() throws Exception {
        mockMvc.perform(get(ROBOTS + "/" + ownSerial + "/task-reports")
                        .param("startTimeMin", "2026-07-01 00:00:00")
                        .header(HEADER, keyOfA))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message")
                        .value(org.hamcrest.Matchers.containsString("date only")));
    }

    @Test
    void startTimeMinTakesPrecedenceOverTheFromAlias() throws Exception {
        mockMvc.perform(get(ROBOTS + "/" + ownSerial + "/task-reports")
                        .param("startTimeMin", "2026-07-15")
                        .param("from", "2026-06-01") // alias ignored when the primary is present
                        .header(HEADER, keyOfA))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.totalElements").value(1));
    }

    @Test
    void reversedDateRangeIsRejected() throws Exception {
        mockMvc.perform(get(ROBOTS + "/" + ownSerial + "/task-reports")
                        .param("from", "2026-07-31")
                        .param("to", "2026-07-01")
                        .header(HEADER, keyOfA))
                .andExpect(status().isBadRequest());
    }

    /** An oversized page request must be clamped, not honoured. */
    @Test
    void pageSizeIsCapped() throws Exception {
        mockMvc.perform(get(ROBOTS + "/" + ownSerial + "/task-reports")
                        .param("size", "5000")
                        .header(HEADER, keyOfA))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.size").value(100));
    }

    /* ── fixtures ─────────────────────────────────────────────────────────── */

    private CustomerProfile customer(String companyName) {
        return customerProfileRepository.save(
                CustomerProfile.builder().companyName(companyName).build());
    }

    private RobotUnit deployedRobot(String serialNumber, String name, CustomerProfile customer, UUID partnerId) {
        RobotUnit robot = robotUnitRepository.save(RobotUnit.builder()
                .serialNumber(serialNumber)
                .brand("GAUSIUM")
                .model("M50")
                .name(name)
                .build());
        deploymentRepository.save(Deployment.builder()
                .robotUnit(robot)
                .customerProfile(customer)
                .site("Test site")
                .partnerId(partnerId)
                .isActive(true)
                .deployedAt(LocalDateTime.now())
                .build());
        return robot;
    }

    private void taskReport(RobotUnit robot, CustomerProfile customer,
                            String externalTaskId, Instant startTime, String reportMonth) {
        taskReportRepository.save(RobotTaskReport.builder()
                .externalTaskId(externalTaskId)
                .robotUnit(robot)
                .customerProfile(customer)
                .brand("GAUSIUM")
                .startTime(startTime)
                .endTime(startTime.plusSeconds(3600))
                .reportMonth(reportMonth)
                .syncedAt(Instant.now())
                .build());
    }
}
