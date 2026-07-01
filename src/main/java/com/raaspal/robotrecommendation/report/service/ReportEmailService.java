package com.raaspal.robotrecommendation.report.service;

import com.raaspal.robotrecommendation.common.exception.BadRequestException;
import com.raaspal.robotrecommendation.common.exception.ResourceNotFoundException;
import com.raaspal.robotrecommendation.customer.entity.CustomerProfile;
import com.raaspal.robotrecommendation.customer.repository.CustomerProfileRepository;
import com.raaspal.robotrecommendation.robotunit.dto.RobotUnitResponse;
import com.raaspal.robotrecommendation.robotunit.service.RobotUnitService;
import jakarta.mail.internet.MimeMessage;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.mail.javamail.MimeMessageHelper;
import org.springframework.stereotype.Service;

import java.time.Month;
import java.time.YearMonth;
import java.time.format.TextStyle;
import java.util.Locale;
import java.util.UUID;

/**
 * Emails a customer a link to their monthly report (the public token page). The
 * report itself is the web page — the email just carries the link, so there is
 * no attachment. Sending fails clearly (BadRequestException) until SMTP
 * credentials are configured or when the customer has no contact email.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ReportEmailService {

    private final RobotUnitService robotUnitService;
    private final CustomerProfileRepository customerProfileRepository;
    private final ReportLinkService reportLinkService;
    private final CustomerReportLinkService customerReportLinkService;
    private final JavaMailSender mailSender;

    @Value("${app.mail.from}")
    private String from;

    @Value("${app.public.base-url}")
    private String baseUrl;

    @Value("${app.public.report-locale}")
    private String reportLocale;

    /** What was sent, for the API response. */
    public record SentEmail(String recipient, String customerName, String url) {
    }

    /** Builds the report link for a robot+month and emails it to its customer. */
    public SentEmail send(String serialNumber, String month) {
        RobotUnitResponse robot = robotUnitService.getBySerialNumber(serialNumber);
        if (robot.deployment() == null) {
            throw new BadRequestException("Robot " + serialNumber + " is not deployed to a customer.");
        }
        CustomerProfile customer = customerProfileRepository.findById(robot.deployment().customerProfileId())
                .orElseThrow(() -> new ResourceNotFoundException("CustomerProfile", "id", robot.deployment().customerProfileId()));

        String email = customer.getContactEmail();
        if (email == null || email.isBlank()) {
            throw new BadRequestException("Customer '" + customer.getCompanyName()
                    + "' has no contact email. Add one in the Customers tab first.");
        }

        String token = reportLinkService.createOrGetToken(serialNumber, month);
        String url = baseUrl.replaceAll("/+$", "") + "/" + reportLocale + "/report/" + token;
        String periodLabel = periodLabel(month);

        try {
            MimeMessage message = mailSender.createMimeMessage();
            MimeMessageHelper helper = new MimeMessageHelper(message, false, "UTF-8");
            helper.setFrom(from);
            helper.setTo(email);
            helper.setSubject("RAAS PAL — Monthly Robot Performance Report (" + periodLabel + ")");
            helper.setText(buildHtml(customer.getCompanyName(), periodLabel, url), true);
            mailSender.send(message);
        } catch (Exception e) {
            log.error("Failed to email report for {} to {}: {}", serialNumber, email, e.getMessage(), e);
            throw new BadRequestException("Email send failed: " + e.getMessage()
                    + " (check MAIL_USERNAME / MAIL_PASSWORD).");
        }

        log.info("Report email sent for {} to {}", serialNumber, email);
        return new SentEmail(email, customer.getCompanyName(), url);
    }

    /**
     * Emails a customer a single link covering ALL of their robots for the month
     * (the combined report bundle page). Used by the automated monthly scheduler.
     */
    public SentEmail sendBundle(UUID customerProfileId, String month) {
        CustomerProfile customer = customerProfileRepository.findById(customerProfileId)
                .orElseThrow(() -> new ResourceNotFoundException("CustomerProfile", "id", customerProfileId));

        String email = customer.getContactEmail();
        if (email == null || email.isBlank()) {
            throw new BadRequestException("Customer '" + customer.getCompanyName()
                    + "' has no contact email. Add one in the Customers tab first.");
        }

        String token = customerReportLinkService.createOrGetToken(customerProfileId, month);
        String url = baseUrl.replaceAll("/+$", "") + "/" + reportLocale + "/report/customer/" + token;
        String periodLabel = periodLabel(month);

        try {
            MimeMessage message = mailSender.createMimeMessage();
            MimeMessageHelper helper = new MimeMessageHelper(message, false, "UTF-8");
            helper.setFrom(from);
            helper.setTo(email);
            helper.setSubject("RAAS PAL — Monthly Robot Performance Report (" + periodLabel + ")");
            helper.setText(buildHtml(customer.getCompanyName(), periodLabel, url), true);
            mailSender.send(message);
        } catch (Exception e) {
            log.error("Failed to email bundle report for customer {} to {}: {}",
                    customerProfileId, email, e.getMessage(), e);
            throw new BadRequestException("Email send failed: " + e.getMessage()
                    + " (check MAIL_USERNAME / MAIL_PASSWORD).");
        }

        log.info("Bundle report email sent for customer {} to {}", customerProfileId, email);
        return new SentEmail(email, customer.getCompanyName(), url);
    }

    private String buildHtml(String company, String periodLabel, String url) {
        return """
                <div style="font-family: Arial, Helvetica, sans-serif; color:#16243a; max-width:520px; line-height:1.55;">
                  <p style="color:#0f7ea8; font-weight:bold; font-size:18px; margin:0 0 2px;">RAAS PAL</p>
                  <p style="font-weight:bold; margin:0 0 16px;">Monthly Robot Performance Report</p>
                  <p>Dear %s,</p>
                  <p>Please find your Monthly Robot Performance Report for <strong>%s</strong>. You can view the
                     full report online, and download a PDF copy from the report page.</p>
                  <p style="margin:24px 0;">
                    <a href="%s" style="display:inline-block; background:#16b9d1; color:#ffffff;
                       padding:12px 22px; border-radius:8px; text-decoration:none; font-weight:bold;">View report</a>
                  </p>
                  <p style="color:#6b7785; font-size:12px;">Or open this link:<br><a href="%s">%s</a></p>
                  <p style="margin-top:20px;">Should you have any questions, please contact your RAASPAL representative.</p>
                  <p style="margin:0;">Best regards,<br>RAASPAL Team</p>
                  <p style="color:#6b7785; font-size:12px; margin-top:24px;">
                    Figures are generated automatically from robot telemetry. Final confirmation requires
                    RAASPAL verification and/or an on-site survey.
                  </p>
                </div>
                """.formatted(escape(company), escape(periodLabel), url, url, url);
    }

    /** "2026-06" → "June 2026". */
    private String periodLabel(String month) {
        try {
            YearMonth ym = YearMonth.parse(month);
            return Month.of(ym.getMonthValue()).getDisplayName(TextStyle.FULL, Locale.ENGLISH) + " " + ym.getYear();
        } catch (Exception e) {
            return month;
        }
    }

    private String escape(String s) {
        return s == null ? "" : s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;");
    }
}
