package com.raaspal.robotrecommendation.reassignment.service;

import com.raaspal.robotrecommendation.reassignment.config.ReAssignmentProperties;
import com.raaspal.robotrecommendation.reassignment.entity.ReAssignment;
import com.raaspal.robotrecommendation.reassignment.entity.ReEngineer;
import com.raaspal.robotrecommendation.reassignment.entity.ReTicket;
import jakarta.mail.internet.MimeMessage;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.mail.javamail.MimeMessageHelper;
import org.springframework.stereotype.Service;
import org.springframework.web.util.HtmlUtils;

import java.nio.charset.StandardCharsets;
import java.util.Objects;

/**
 * The assignment email to the engineer, in Thai and English.
 *
 * <p>Internal only: ticket, customer site, robot, issue, difficulty and the monday link.
 * Never the engineer's skill levels or score - those are the Senior RE's assessment.
 *
 * <p>Off unless {@code app.re-assignment.email.enabled}; {@code redirect-to} sends every
 * message to one test address instead. Returns a status rather than throwing, so a mail
 * failure never undoes an approval.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ReAssignmentEmailService {

    public record Outcome(String status, String detail) {
    }

    private final JavaMailSender mailSender;
    private final ReAssignmentProperties props;

    @Value("${app.mail.from:RAASPAL Reports <no-reply@raaspal.com>}")
    private String from;

    public Outcome send(ReAssignment a, ReEngineer e, ReTicket t, String mondayUrl) {
        String to = e.getEmail();
        if (to == null || to.isBlank()) return new Outcome("NO_ADDRESS", "No email recorded for " + e.displayName());
        String redirect = props.getEmail().getRedirectTo();
        String actualTo = redirect == null || redirect.isBlank() ? to : redirect.trim();
        if (!props.getEmail().isEnabled()) {
            return new Outcome("DISABLED", "Email is switched off (RE_ASSIGNMENT_EMAIL_ENABLED); would send to " + to);
        }
        try {
            MimeMessage msg = mailSender.createMimeMessage();
            MimeMessageHelper h = new MimeMessageHelper(msg, false, StandardCharsets.UTF_8.name());
            h.setFrom(from);
            h.setTo(actualTo);
            h.setSubject(subject(t));
            h.setText(html(e, t, mondayUrl, !actualTo.equals(to) ? to : null), true);
            mailSender.send(msg);
            return new Outcome("SENT", actualTo.equals(to) ? "Sent to " + to : "Sent to " + actualTo + " (redirected from " + to + ")");
        } catch (Exception ex) {
            log.warn("RE assignment email to {} failed: {}", actualTo, ex.getMessage());
            return new Outcome("FAILED", ex.getClass().getSimpleName() + ": " + Objects.toString(ex.getMessage(), ""));
        }
    }

    static String subject(ReTicket t) {
        String where = Objects.toString(t.getCustomer(), "");
        String robot = Objects.toString(t.getModelLabel(), "");
        return "[RAASPAL] งาน CM ใหม่ / New CM task - " + (where.isBlank() ? t.getItemId() : where)
                + (robot.isBlank() ? "" : " · " + robot);
    }

    static String html(ReEngineer e, ReTicket t, String mondayUrl, String redirectedFrom) {
        StringBuilder rows = new StringBuilder();
        row(rows, "ลูกค้า / Customer", t.getCustomer());
        row(rows, "สาขา / Branch", t.getBranch());
        row(rows, "หุ่นยนต์ / Robot", join(t.getModelLabel(), t.getSerialNumber()));
        row(rows, "อาการ / Issue", t.getMainIssue());
        row(rows, "ระดับความยาก / Difficulty", t.getIssueLevel());
        row(rows, "ประเภทเคส / Case type", t.getCaseType());
        row(rows, "Online / On Site", t.getServiceMode());
        row(rows, "วันที่เปิดเคส / Opened", t.getOpenDate() == null ? null : t.getOpenDate().toString());
        row(rows, "Ticket", join(t.getItemName(), "#" + t.getItemId()));
        String link = mondayUrl == null ? ""
                : "<p><a href=\"" + HtmlUtils.htmlEscape(mondayUrl) + "\">เปิดใน monday / Open in monday</a></p>";
        String note = redirectedFrom == null ? ""
                : "<p style=\"color:#b45309\">[TEST] Redirected - intended for " + HtmlUtils.htmlEscape(redirectedFrom) + "</p>";
        return "<div style=\"font-family:Arial,sans-serif;font-size:14px;color:#16243a\">" + note
                + "<p>เรียน " + esc(e.getNickname() != null ? e.getNickname() : e.getFullName()) + ",</p>"
                + "<p>คุณได้รับมอบหมายงานซ่อม (CM) ใหม่ตามรายละเอียดด้านล่าง กรุณาตรวจสอบและอัปเดตสถานะใน monday<br>"
                + "You have been assigned a new corrective-maintenance task. Please review it and update its status in monday.</p>"
                + "<table style=\"border-collapse:collapse\">" + rows + "</table>" + link
                + "<p style=\"color:#6b7785;font-size:12px\">RAAS PAL Operations - อีเมลอัตโนมัติ / automatic email</p></div>";
    }

    private static void row(StringBuilder sb, String label, String value) {
        if (value == null || value.isBlank()) return;
        sb.append("<tr><td style=\"padding:4px 12px 4px 0;color:#6b7785;vertical-align:top\">").append(esc(label))
                .append("</td><td style=\"padding:4px 0\">").append(esc(value)).append("</td></tr>");
    }

    private static String join(String a, String b) {
        boolean ha = a != null && !a.isBlank();
        boolean hb = b != null && !b.isBlank();
        return ha && hb ? a + " · " + b : ha ? a : hb ? b : null;
    }

    private static String esc(String s) {
        return HtmlUtils.htmlEscape(Objects.toString(s, ""));
    }
}
