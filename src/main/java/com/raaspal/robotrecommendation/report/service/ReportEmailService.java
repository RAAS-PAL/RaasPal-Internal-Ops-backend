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
import org.springframework.core.io.ClassPathResource;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.mail.javamail.MimeMessageHelper;
import org.springframework.stereotype.Service;

import java.time.Month;
import java.time.YearMonth;
import java.time.format.TextStyle;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.UUID;

/**
 * Emails a customer a link to their monthly or weekly report (the public token page). The
 * report itself is the web page — the email just carries the link, so there is
 * no attachment. Sending fails clearly (BadRequestException) until SMTP
 * credentials are configured or when the customer has no contact email.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ReportEmailService {

    /** "Robot performance summary report" — every report email's subject starts with it. */
    private static final String SUBJECT_PREFIX = "รายงานสรุปผลการใช้งานหุ่นยนต์ ";
    /** "For the month of" — followed by e.g. "August 2026". */
    private static final String MONTH_WORD = "ประจำเดือน ";
    /** "For the week of" — followed by e.g. "14 – 20 September 2026". */
    private static final String WEEK_WORD = "ประจำสัปดาห์ ";

    private final RobotUnitService robotUnitService;
    private final CustomerProfileRepository customerProfileRepository;
    private final ReportLinkService reportLinkService;
    private final CustomerReportLinkService customerReportLinkService;
    private final JavaMailSender mailSender;

    @Value("${app.mail.from}")
    private String from;

    /**
     * Standing CC for report emails — the RAASPAL team addresses that keep a copy
     * of what customers receive. Empty means no CC.
     * <p>
     * Deliberately scoped to this service. Announcements have their own per-send CC
     * box, and a standing CC there would silently widen the audience of a one-off
     * message the sender thought they were addressing narrowly.
     */
    @Value("${app.mail.cc:}")
    private String cc;

    @Value("${app.public.base-url}")
    private String baseUrl;

    @Value("${app.public.report-locale}")
    private String reportLocale;

    /** What was sent, for the API response. */
    public record SentEmail(String recipient, String customerName, String url) {
    }

    /**
     * Builds the report link for a robot and period — a month or an ISO week — and
     * emails it to its customer. The subject and body name the period the way the
     * report does: "ประจำเดือน August 2026" or "ประจำสัปดาห์ 14 – 20 September 2026".
     */
    public SentEmail send(String serialNumber, ReportPeriod period) {
        RobotUnitResponse robot = robotUnitService.getBySerialNumber(serialNumber);
        if (robot.deployment() == null) {
            throw new BadRequestException("Robot " + serialNumber + " is not deployed to a customer.");
        }
        CustomerProfile customer = customerProfileRepository.findById(robot.deployment().customerProfileId())
                .orElseThrow(() -> new ResourceNotFoundException("CustomerProfile", "id", robot.deployment().customerProfileId()));

        List<String> recipients = recipientsOf(customer);
        String token = reportLinkService.createOrGetToken(serialNumber, period);
        String url = baseUrl.replaceAll("/+$", "") + "/" + reportLocale + "/report/" + token;
        String periodPhrase = periodPhrase(period);
        String subject = SUBJECT_PREFIX + periodPhrase;
        String html = buildHtml(periodPhrase, url);

        sendToAll(recipients, subject, html, "report for " + serialNumber);

        log.info("Report email sent for {} to {}", serialNumber, recipients);
        return new SentEmail(String.join(", ", recipients), customer.getCompanyName(), url);
    }

    /**
     * Emails a customer a single link covering ALL of their robots for the month
     * (the combined report bundle page). Used by the automated monthly scheduler.
     */
    public SentEmail sendBundle(UUID customerProfileId, String month) {
        CustomerProfile customer = customerProfileRepository.findById(customerProfileId)
                .orElseThrow(() -> new ResourceNotFoundException("CustomerProfile", "id", customerProfileId));

        List<String> recipients = recipientsOf(customer);
        String token = customerReportLinkService.createOrGetToken(customerProfileId, month);
        String url = baseUrl.replaceAll("/+$", "") + "/" + reportLocale + "/report/customer/" + token;
        String periodPhrase = MONTH_WORD + periodLabel(month);
        String subject = SUBJECT_PREFIX + periodPhrase;
        String html = buildHtml(periodPhrase, url);

        sendToAll(recipients, subject, html, "bundle for customer " + customerProfileId);

        log.info("Bundle report email sent for customer {} to {}", customerProfileId, recipients);
        return new SentEmail(String.join(", ", recipients), customer.getCompanyName(), url);
    }

    /**
     * Sends one email addressed to all recipients on the To line. A customer
     * (one branch) may list several contact emails, comma/semicolon-separated in
     * {@code contactEmail}; they all receive the same single message. Different
     * branches are separate customer records and are emailed independently.
     */
    private void sendToAll(List<String> recipients, String subject, String html, String context) {
        List<String> ccList = ccAddresses(recipients);
        try {
            MimeMessage message = mailSender.createMimeMessage();
            // multipart=true is required for addInline below — the footer banner is
            // carried in the message rather than fetched from a URL.
            MimeMessageHelper helper = new MimeMessageHelper(message, true, "UTF-8");
            helper.setFrom(from);
            helper.setTo(recipients.toArray(new String[0]));
            if (!ccList.isEmpty()) {
                helper.setCc(ccList.toArray(new String[0]));
            }
            helper.setSubject(subject);
            // setText before addInline: the helper needs the body part to exist before
            // an inline part can be related to it, or the image arrives as a plain
            // attachment instead of rendering in place.
            helper.setText(html, true);
            attachFooterImage(helper);
            mailSender.send(message);
        } catch (Exception e) {
            log.error("Failed to email {} to {}: {}", context, recipients, e.getMessage(), e);
            throw new BadRequestException("Email send failed: " + e.getMessage()
                    + " (check MAIL_USERNAME / MAIL_PASSWORD).");
        }
    }

    /** Content-ID the HTML body references for the footer banner. */
    private static final String FOOTER_IMAGE_CID = "raaspalFooterBanner";

    private static final String FOOTER_IMAGE_PATH = "email/email-footer-banner.png";

    /**
     * Attaches the RAAS PAL footer banner as an inline part.
     * <p>
     * Deliberately non-fatal: a missing or unreadable image leaves a broken
     * placeholder at the bottom of the mail, which is far better than failing the
     * monthly send for every customer over a decorative asset.
     */
    private void attachFooterImage(MimeMessageHelper helper) {
        ClassPathResource image = new ClassPathResource(FOOTER_IMAGE_PATH);
        if (!image.exists()) {
            log.warn("Email footer image {} not found on the classpath — sending without it", FOOTER_IMAGE_PATH);
            return;
        }
        try {
            helper.addInline(FOOTER_IMAGE_CID, image, "image/png");
        } catch (Exception e) {
            log.warn("Could not attach the email footer image: {}", e.getMessage());
        }
    }

    /**
     * The configured CC list, minus anyone already on the To line.
     * <p>
     * The overlap matters: a colleague listed as a customer contact would otherwise
     * receive the same mail twice and appear in both headers, which looks like a
     * bug to the customer reading it.
     */
    private List<String> ccAddresses(List<String> recipients) {
        List<String> to = recipients.stream().map(s -> s.toLowerCase(Locale.ROOT)).toList();
        return splitAddresses(cc).stream()
                .filter(address -> !to.contains(address.toLowerCase(Locale.ROOT)))
                .toList();
    }

    /** Splits a comma/semicolon separated address list, trimming blanks and duplicates. */
    private static List<String> splitAddresses(String raw) {
        if (raw == null || raw.isBlank()) return List.of();
        return Arrays.stream(raw.split("[,;]"))
                .map(String::trim)
                .filter(s -> !s.isBlank())
                .distinct()
                .toList();
    }

    /**
     * The customer's contact address(es). {@code contactEmail} may hold several
     * addresses separated by comma or semicolon; each is trimmed and blanks are
     * dropped. Throws if none are usable.
     */
    private List<String> recipientsOf(CustomerProfile customer) {
        List<String> recipients = splitAddresses(customer.getContactEmail());
        if (recipients.isEmpty()) {
            throw new BadRequestException("Customer '" + customer.getCompanyName()
                    + "' has no contact email. Add one in the Customers tab first.");
        }
        return recipients;
    }

    /**
     * "ประจำเดือน August 2026" or "ประจำสัปดาห์ 14 – 20 September 2026" — the Thai
     * "for the month of" / "for the week of" plus the same label the report page shows.
     */
    private static String periodPhrase(ReportPeriod period) {
        return (period.type() == ReportPeriod.Type.WEEK ? WEEK_WORD : MONTH_WORD) + period.label();
    }

    private String buildHtml(String periodPhrase, String url) {
        return """
                <div style="font-family: 'Segoe UI', Tahoma, Arial, sans-serif; color:#16243a; max-width:560px; line-height:1.7;">
                  <p style="color:#1d4ed8; font-weight:bold; font-size:18px; margin:0 0 16px;">RAAS PAL</p>
                  <p style="margin:0 0 14px;">เรียน&nbsp;&nbsp;&nbsp;ผู้บริหารโครงการและผู้ที่เกี่ยวข้อง</p>
                  <p style="margin:0 0 14px;">เพื่อให้ท่านสามารถติดตามประสิทธิภาพการทำงานของหุ่นยนต์ได้อย่างต่อเนื่อง
                     RAASPAL ขอส่ง <strong>รายงานสรุปผลการใช้งานหุ่นยนต์ (Executive Robot Performance Report)
                     %s</strong> มาเพื่อประกอบการพิจารณา</p>
                  <p style="margin:0 0 6px;">รายงานฉบับนี้สรุปข้อมูลสำคัญ ได้แก่</p>
                  <ul style="margin:0 0 16px; padding-left:22px;">
                    <li>ภาพรวมผลการปฏิบัติงานของหุ่นยนต์</li>
                    <li>ประสิทธิภาพการทำงาน (Operational Performance)</li>
                    <li>สถานะวัสดุสิ้นเปลือง (Consumables Status)</li>
                    <li>ข้อเสนอแนะเพื่อการใช้งานอย่างมีประสิทธิภาพ</li>
                  </ul>
                  <p style="margin:24px 0;">
                    <a href="%s" style="display:inline-block; background:#2563eb; color:#ffffff;
                       padding:12px 24px; border-radius:8px; text-decoration:none; font-weight:bold;">ดูรายงาน</a>
                  </p>
                  <p style="color:#6b7785; font-size:12px; margin:0 0 20px;">หรือเปิดลิงก์นี้:<br><a href="%s">%s</a></p>
                  <p style="margin:0 0 14px;">หากท่านมีข้อสงสัย หรือต้องการข้อมูลเพิ่มเติม สามารถติดต่อ Customer Success Team
                     ผ่านช่องทางกลุ่ม Line หรือ Call Center 02 576 5555</p>
                  <p style="margin:0 0 20px;">ขอขอบพระคุณที่ให้ความไว้วางใจ RAASPAL ในการดูแลระบบหุ่นยนต์ของท่าน</p>
                  <p style="margin:0 0 24px;">ขอแสดงความนับถือ<br>Customer Success Team</p>
                  <!-- Referenced by CID rather than a hosted URL: an inline part renders
                       without the recipient having to click "display images", and does not
                       break if the frontend is redeployed or moved. width/height are set as
                       attributes as well as CSS because Outlook ignores the style. -->
                  <img src="cid:%s" alt="RAAS PAL — Leader in Service Robot Design Solutions"
                       width="560" style="display:block; width:100%%; max-width:560px; height:auto; border:0;">
                </div>
                """.formatted(escape(periodPhrase), url, url, url, FOOTER_IMAGE_CID);
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
