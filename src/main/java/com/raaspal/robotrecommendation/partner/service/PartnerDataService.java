package com.raaspal.robotrecommendation.partner.service;

import com.raaspal.robotrecommendation.common.exception.BadRequestException;
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

import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZoneId;
import java.time.format.DateTimeParseException;
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

    /**
     * The {@code from}/{@code to} day filters are interpreted in this zone — the
     * robots and partners are Thailand-based, so "2026-07-15" means that calendar
     * day in Bangkok, not UTC. Stored start times are UTC instants and matched
     * against the resulting UTC window.
     */
    private static final ZoneId REPORT_ZONE = ZoneId.of("Asia/Bangkok");

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
     * Paged task reports for one of the partner's robots, most recent first.
     * Filtering precedence:
     * <ol>
     *   <li>{@code from}/{@code to} ({@code YYYY-MM-DD}) — a day or date range on
     *       start time. A lone {@code from} (or {@code to}) means that single day.</li>
     *   <li>{@code month} ({@code YYYY-MM}).</li>
     *   <li>none — all reports for the robot.</li>
     * </ol>
     * If the serial number is unknown <em>or</em> belongs to a robot this partner
     * does not service, the call 404s with an identical message either way, so a
     * partner cannot probe for robots outside its scope.
     */
    @Transactional(readOnly = true)
    public PagedResponse<PartnerTaskReportResponse> listTaskReports(
            UUID partnerId, String serialNumber, String month, String from, String to, int page, int size) {

        RobotUnit robot = requirePartnerRobot(partnerId, serialNumber);
        Pageable pageable = PageRequest.of(Math.max(page, 0), clampSize(size));

        boolean hasFrom = from != null && !from.isBlank();
        boolean hasTo = to != null && !to.isBlank();

        Page<RobotTaskReport> reports;
        if (hasFrom || hasTo) {
            // A lone bound means a single day; a range uses both.
            LocalDate fromDate = parseDate(hasFrom ? from : to, "from");
            LocalDate toDate = parseDate(hasTo ? to : from, "to");
            if (toDate.isBefore(fromDate)) {
                throw new BadRequestException("'to' date must not be before 'from' date");
            }
            Instant startMin = fromDate.atStartOfDay(REPORT_ZONE).toInstant();
            Instant startMax = toDate.atTime(LocalTime.MAX).atZone(REPORT_ZONE).toInstant();
            reports = robotTaskReportRepository.findByRobotUnitIdAndStartTimeBetweenOrderByStartTimeDesc(
                    robot.getId(), startMin, startMax, pageable);
        } else if (month != null && !month.isBlank()) {
            reports = robotTaskReportRepository.findByRobotUnitIdAndReportMonthOrderByStartTimeDesc(
                    robot.getId(), month.trim(), pageable);
        } else {
            reports = robotTaskReportRepository.findByRobotUnitIdOrderByStartTimeDesc(
                    robot.getId(), pageable);
        }

        return PagedResponse.of(reports, r -> PartnerTaskReportResponse.of(r, robot.getSerialNumber()));
    }

    private LocalDate parseDate(String value, String field) {
        try {
            return LocalDate.parse(value.trim());
        } catch (DateTimeParseException e) {
            throw new BadRequestException(
                    "Invalid '" + field + "' date '" + value + "' — expected format YYYY-MM-DD");
        }
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
