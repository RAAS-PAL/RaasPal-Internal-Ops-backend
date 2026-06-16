package com.raaspal.robotrecommendation.report.service;

import com.raaspal.robotrecommendation.common.exception.BadRequestException;
import com.raaspal.robotrecommendation.report.core.ReportGenerator;
import com.raaspal.robotrecommendation.report.core.ReportGeneratorRegistry;
import com.raaspal.robotrecommendation.report.delivery.MonthlyReportPayload;
import com.raaspal.robotrecommendation.report.delivery.MonthlyReportPayload.RobotReportLink;
import com.raaspal.robotrecommendation.report.delivery.N8nReportClient;
import com.raaspal.robotrecommendation.report.storage.SupabaseStorageService;
import com.raaspal.robotrecommendation.robotunit.entity.RobotUnit;
import com.raaspal.robotrecommendation.telemetry.entity.RobotTaskReport;
import com.raaspal.robotrecommendation.telemetry.repository.RobotTaskReportRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.YearMonth;
import java.time.ZoneOffset;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Builds and delivers the monthly cleaning reports. For a given month it loads
 * every synced task report, generates one xlsx per robot, uploads each to
 * Supabase Storage (signed URL), and POSTs one bundled payload per customer to
 * the n8n webhook — which performs the LINE Messaging API push.
 *
 * <p>In {@code testMode} the files are still generated and uploaded (so a real
 * download URL can be verified internally), but nothing is sent to n8n/LINE.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class MonthlyReportService {

    private final RobotTaskReportRepository taskReportRepository;
    private final ReportGeneratorRegistry generatorRegistry;
    private final SupabaseStorageService storageService;
    private final N8nReportClient n8nReportClient;

    /**
     * Generates and (unless {@code testMode}) delivers the monthly reports.
     *
     * @param month    target month {@code "YYYY-MM"}; defaults to the previous month when null/blank
     * @param testMode when true, generate + upload only — do not POST to n8n/LINE
     */
    @Transactional(readOnly = true)
    public MonthlyReportSummary generateAndSend(String month, boolean testMode) {
        String reportMonth = resolveMonth(month);

        if (!storageService.isConfigured()) {
            throw new BadRequestException(
                    "Supabase Storage is not configured (set app.supabase.url and app.supabase.service-key).");
        }
        if (!testMode && !n8nReportClient.isConfigured()) {
            throw new BadRequestException(
                    "n8n webhook is not configured (set app.reports.n8n.webhook-url), so a live send cannot run. "
                            + "Use testMode=true to generate and verify files without sending.");
        }

        List<RobotTaskReport> reports = taskReportRepository.findByReportMonthWithRefs(reportMonth);
        if (reports.isEmpty()) {
            log.info("Monthly report {}: no task reports found", reportMonth);
            return new MonthlyReportSummary(reportMonth, testMode, 0, 0, 0, 0, 0, 0, List.of());
        }

        int customersProcessed = 0;
        int robotsReported = 0;
        int messagesSent = 0;
        int recipientsSkipped = 0;
        int robotErrors = 0;
        int sendErrors = 0;
        List<MonthlyReportPayload> previews = new ArrayList<>();

        for (Map.Entry<UuidKey, List<RobotTaskReport>> customerEntry : groupByCustomer(reports).entrySet()) {
            List<RobotTaskReport> customerReports = customerEntry.getValue();
            var customer = customerReports.get(0).getCustomerProfile();

            List<RobotReportLink> links = new ArrayList<>();
            for (Map.Entry<UuidKey, List<RobotTaskReport>> robotEntry : groupByRobot(customerReports).entrySet()) {
                List<RobotTaskReport> robotReports = robotEntry.getValue();
                RobotUnit robot = robotReports.get(0).getRobotUnit();
                try {
                    String url = generateAndUpload(customer.getId().toString(), reportMonth, robot, robotReports);
                    links.add(new RobotReportLink(robot.getName(), robot.getSerialNumber(), url));
                    robotsReported++;
                } catch (Exception e) {
                    robotErrors++;
                    log.error("Monthly report {}: failed for robot {} (customer {}): {}",
                            reportMonth, robot.getSerialNumber(), customer.getCompanyName(), e.getMessage(), e);
                }
            }

            if (links.isEmpty()) {
                continue;
            }
            customersProcessed++;

            String lineUserId = customer.getLineUserId();
            MonthlyReportPayload payload = new MonthlyReportPayload(
                    customer.getId().toString(), customer.getCompanyName(), lineUserId, reportMonth, links);

            if (testMode) {
                previews.add(payload);
                if (lineUserId == null || lineUserId.isBlank()) {
                    recipientsSkipped++;
                }
                continue;
            }

            if (lineUserId == null || lineUserId.isBlank()) {
                recipientsSkipped++;
                log.warn("Monthly report {}: customer {} has no lineUserId — generated {} file(s) but not sent",
                        reportMonth, customer.getCompanyName(), links.size());
                continue;
            }
            try {
                n8nReportClient.send(payload);
                messagesSent++;
            } catch (Exception e) {
                sendErrors++;
                log.error("Monthly report {}: n8n send failed for customer {}: {}",
                        reportMonth, customer.getCompanyName(), e.getMessage(), e);
            }
        }

        log.info("Monthly report {} ({}): {} customer(s), {} file(s), {} sent, {} skipped, {} robotErrors, {} sendErrors",
                reportMonth, testMode ? "TEST" : "LIVE",
                customersProcessed, robotsReported, messagesSent, recipientsSkipped, robotErrors, sendErrors);

        return new MonthlyReportSummary(reportMonth, testMode, customersProcessed, robotsReported,
                messagesSent, recipientsSkipped, robotErrors, sendErrors, previews);
    }

    private String generateAndUpload(String customerId, String reportMonth, RobotUnit robot,
                                     List<RobotTaskReport> robotReports) throws java.io.IOException {
        ReportGenerator generator = generatorRegistry.getGenerator(robot.getBrand());
        byte[] xlsx = generator.generate(robotReports);
        String objectPath = customerId + "/" + reportMonth + "/" + robot.getSerialNumber() + ".xlsx";
        return storageService.uploadAndSign(objectPath, xlsx);
    }

    private String resolveMonth(String month) {
        if (month == null || month.isBlank()) {
            return YearMonth.now(ZoneOffset.UTC).minusMonths(1).toString();
        }
        try {
            return YearMonth.parse(month.trim()).toString();
        } catch (DateTimeParseException e) {
            throw new BadRequestException("Invalid month '" + month + "'; expected format YYYY-MM (e.g. 2026-05).");
        }
    }

    private Map<UuidKey, List<RobotTaskReport>> groupByCustomer(List<RobotTaskReport> reports) {
        Map<UuidKey, List<RobotTaskReport>> grouped = new LinkedHashMap<>();
        for (RobotTaskReport report : reports) {
            grouped.computeIfAbsent(new UuidKey(report.getCustomerProfile().getId()), k -> new ArrayList<>()).add(report);
        }
        return grouped;
    }

    private Map<UuidKey, List<RobotTaskReport>> groupByRobot(List<RobotTaskReport> reports) {
        Map<UuidKey, List<RobotTaskReport>> grouped = new LinkedHashMap<>();
        for (RobotTaskReport report : reports) {
            grouped.computeIfAbsent(new UuidKey(report.getRobotUnit().getId()), k -> new ArrayList<>()).add(report);
        }
        return grouped;
    }

    /** Keys grouping maps by UUID value (entity identity), preserving insertion order. */
    private record UuidKey(java.util.UUID id) {
    }
}
