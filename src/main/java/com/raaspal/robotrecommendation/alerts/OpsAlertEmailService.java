package com.raaspal.robotrecommendation.alerts;

import com.raaspal.robotrecommendation.robotunit.dto.ContractExpiryResponse;
import com.raaspal.robotrecommendation.telemetry.dto.ZeroDataRobotsResponse;
import jakarta.mail.internet.MimeMessage;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.mail.javamail.MimeMessageHelper;
import org.springframework.stereotype.Service;
import org.springframework.web.util.HtmlUtils;

import java.util.Arrays;
import java.util.List;

/**
 * Internal alert emails to the customer success team.
 *
 * <p>Separate from {@code ReportEmailService} on purpose: that one writes to
 * customers, with the company footer and the report's look; these are short plain
 * tables to colleagues, and must not pick up customer-facing dressing by accident.
 *
 * <p>Off until {@code app.alerts.cs-email} is set. Never throws — a failed alert is a
 * log line, not a crashed scheduler.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class OpsAlertEmailService {

    private final JavaMailSender mailSender;

    @Value("${app.alerts.cs-email:}")
    private String csEmail;

    @Value("${app.mail.from:RAASPAL Reports <no-reply@raaspal.com>}")
    private String from;

    @Value("${app.public.base-url:http://localhost:3000}")
    private String consoleBaseUrl;

    public boolean isConfigured() {
        return csEmail != null && !csEmail.isBlank();
    }

    /** One email listing the contracts that just entered the ending-soon window. */
    public boolean sendContractExpiryAlert(List<ContractExpiryResponse.Contract> contracts, int windowDays) {
        if (contracts.isEmpty()) return false;
        StringBuilder rows = new StringBuilder();
        for (ContractExpiryResponse.Contract c : contracts) {
            rows.append("<tr>")
                .append(td(c.customerName())).append(td(c.site()))
                .append(td(c.serialNumber())).append(td(join(c.name(), c.brand(), c.model())))
                .append(td(String.valueOf(c.contractEndDate())))
                .append(td(c.daysToEnd() + " days"))
                .append("</tr>");
        }
        String html = "<p>" + contracts.size() + " robot contract(s) end within the next " + windowDays
                + " days. Please arrange the renewal or set the robot's end date accordingly.</p>"
                + table("Customer", "Site", "Serial", "Robot", "Ends", "In", rows)
                + link("/tools?tab=robots", "Open Tools → Robots");
        return send("[RAASPAL] " + contracts.size() + " robot contract(s) ending within " + windowDays + " days", html,
                "contract expiry alert");
    }

    /** The monthly worklist: every in-contract robot that logged nothing last month. */
    public boolean sendZeroDataDigest(ZeroDataRobotsResponse digest) {
        StringBuilder rows = new StringBuilder();
        for (ZeroDataRobotsResponse.Robot r : digest.robots()) {
            rows.append("<tr>")
                .append(td(r.customerName())).append(td(r.site()))
                .append(td(r.serialNumber())).append(td(join(r.name(), r.brand(), r.model())))
                .append(td(reasonLabel(r.reason())))
                .append(td(r.lastDataDate() == null ? "never" : r.lastDataDate().toString()))
                .append("</tr>");
        }
        String intro = digest.robots().isEmpty()
                ? "<p>Every robot under contract in " + esc(digest.monthLabel()) + " logged at least one task. Nothing to follow up.</p>"
                : "<p>" + digest.zeroData() + " of " + digest.inScope() + " robots under contract in "
                  + esc(digest.monthLabel()) + " logged <b>no task</b>. Please contact the customers to find out why, "
                  + "and record the outcome on the No data page.</p>"
                  + table("Customer", "Site", "Serial", "Robot", "Why", "Last data", rows);
        String html = intro + link("/reports?tab=zero-data", "Open the No data page");
        return send("[RAASPAL] Robots with no data — " + digest.monthLabel()
                + " (" + digest.zeroData() + " of " + digest.inScope() + ")", html, "zero-data digest");
    }

    private boolean send(String subject, String html, String context) {
        if (!isConfigured()) {
            log.info("Skipping {} — app.alerts.cs-email is not set", context);
            return false;
        }
        try {
            MimeMessage message = mailSender.createMimeMessage();
            MimeMessageHelper helper = new MimeMessageHelper(message, false, "UTF-8");
            helper.setFrom(from);
            helper.setTo(Arrays.stream(csEmail.split("[,;]")).map(String::strip)
                    .filter(s -> !s.isEmpty()).toArray(String[]::new));
            helper.setSubject(subject);
            helper.setText("<html><body style=\"font-family:Arial,sans-serif;font-size:14px;color:#12263a\">"
                    + html + "</body></html>", true);
            mailSender.send(message);
            log.info("Sent {} to {}", context, csEmail);
            return true;
        } catch (Exception e) {
            log.error("Could not send {}: {}", context, e.getMessage());
            return false;
        }
    }

    private String link(String path, String label) {
        return "<p><a href=\"" + esc(consoleBaseUrl + path) + "\">" + esc(label) + "</a></p>";
    }

    private static String table(String h1, String h2, String h3, String h4, String h5, String h6, StringBuilder rows) {
        return "<table cellpadding=\"6\" cellspacing=\"0\" border=\"1\" style=\"border-collapse:collapse;border-color:#dbe4f0\">"
                + "<tr style=\"background:#bcccea\">" + th(h1) + th(h2) + th(h3) + th(h4) + th(h5) + th(h6) + "</tr>"
                + rows + "</table>";
    }

    private static String reasonLabel(ZeroDataRobotsResponse.Reason reason) {
        return switch (reason) {
            case NEVER_SYNCED -> "Never synced — check serial / brand";
            case SYNC_FAILING -> "Sync failing — our side";
            case NO_TASKS -> "No tasks — robot idle";
        };
    }

    private static String join(String... parts) {
        return String.join(" · ", Arrays.stream(parts).filter(p -> p != null && !p.isBlank()).toList());
    }

    private static String th(String s) {
        return "<th align=\"left\">" + esc(s) + "</th>";
    }

    private static String td(String s) {
        return "<td>" + esc(s == null ? "—" : s) + "</td>";
    }

    private static String esc(String s) {
        return HtmlUtils.htmlEscape(s == null ? "" : s);
    }
}
