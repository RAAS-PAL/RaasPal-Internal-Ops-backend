package com.raaspal.robotrecommendation.partner.service;

import com.raaspal.robotrecommendation.common.exception.ResourceNotFoundException;
import com.raaspal.robotrecommendation.common.response.PagedResponse;
import com.raaspal.robotrecommendation.partner.dto.PartnerRobotResponse;
import com.raaspal.robotrecommendation.partner.dto.PartnerTaskReportResponse;
import com.raaspal.robotrecommendation.robotunit.entity.RobotUnit;
import com.raaspal.robotrecommendation.robotunit.repository.DeploymentRepository;
import com.raaspal.robotrecommendation.robotunit.repository.RobotUnitRepository;
import com.raaspal.robotrecommendation.telemetry.entity.RobotTaskReport;
import com.raaspal.robotrecommendation.telemetry.repository.RobotTaskReportRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.UUID;

/**
 * Read-only data access for the partner API. Every method is scoped to a single
 * partner id (which the caller obtains from the authenticated key, never from a
 * request parameter) so a partner can only ever see robots it services, via
 * {@code deployments.partner_id}, and those robots' already-synced task reports.
 *
 * <p>Data comes entirely from our own tables ({@code deployments},
 * {@code robot_units}, {@code robot_task_reports}) — no live brand-API call is
 * made when a partner reads.
 */
@Service
@RequiredArgsConstructor
public class PartnerDataService {

    /** Hard cap on page size so a partner cannot request an unbounded page. */
    private static final int MAX_PAGE_SIZE = 100;
    private static final int DEFAULT_PAGE_SIZE = 20;

    private final DeploymentRepository deploymentRepository;
    private final RobotUnitRepository robotUnitRepository;
    private final RobotTaskReportRepository robotTaskReportRepository;

    /** The robots this partner services (its active deployments). */
    @Transactional(readOnly = true)
    public List<PartnerRobotResponse> listRobots(UUID partnerId) {
        return deploymentRepository.findByPartnerIdAndIsActiveTrue(partnerId).stream()
                .map(PartnerRobotResponse::of)
                .toList();
    }

    /**
     * Paged task reports for one of the partner's robots, most recent first,
     * optionally filtered to a month ({@code YYYY-MM}). If the serial number is
     * unknown <em>or</em> belongs to a robot this partner does not service, the
     * call 404s with an identical message either way, so a partner cannot probe
     * for robots outside its scope.
     */
    @Transactional(readOnly = true)
    public PagedResponse<PartnerTaskReportResponse> listTaskReports(
            UUID partnerId, String serialNumber, String month, int page, int size) {

        RobotUnit robot = requirePartnerRobot(partnerId, serialNumber);
        Pageable pageable = PageRequest.of(Math.max(page, 0), clampSize(size));

        Page<RobotTaskReport> reports = (month != null && !month.isBlank())
                ? robotTaskReportRepository.findByRobotUnitIdAndReportMonthOrderByStartTimeDesc(
                        robot.getId(), month.trim(), pageable)
                : robotTaskReportRepository.findByRobotUnitIdOrderByStartTimeDesc(
                        robot.getId(), pageable);

        return PagedResponse.of(reports, r -> PartnerTaskReportResponse.of(r, robot.getSerialNumber()));
    }

    /**
     * Resolves a serial number to a robot the partner actually services, or
     * throws a non-revealing {@link ResourceNotFoundException}. The same
     * exception is thrown whether the robot does not exist or simply is not this
     * partner's — existence is never leaked.
     */
    private RobotUnit requirePartnerRobot(UUID partnerId, String serialNumber) {
        String sn = serialNumber == null ? "" : serialNumber.trim();
        return robotUnitRepository.findBySerialNumber(sn)
                .filter(robot -> isServicedBy(partnerId, robot.getId()))
                .orElseThrow(() -> new ResourceNotFoundException(
                        "No robot with serial number '" + sn + "' is available to this partner"));
    }

    private boolean isServicedBy(UUID partnerId, UUID robotUnitId) {
        return deploymentRepository.findByRobotUnitIdAndIsActiveTrue(robotUnitId).stream()
                .anyMatch(deployment -> partnerId.equals(deployment.getPartnerId()));
    }

    private int clampSize(int size) {
        if (size <= 0) {
            return DEFAULT_PAGE_SIZE;
        }
        return Math.min(size, MAX_PAGE_SIZE);
    }
}
