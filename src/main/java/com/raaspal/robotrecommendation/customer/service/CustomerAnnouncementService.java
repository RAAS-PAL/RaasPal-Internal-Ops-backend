package com.raaspal.robotrecommendation.customer.service;

import com.raaspal.robotrecommendation.common.exception.BadRequestException;
import com.raaspal.robotrecommendation.customer.dto.AnnouncementRequest;
import com.raaspal.robotrecommendation.customer.dto.AnnouncementResult;
import com.raaspal.robotrecommendation.customer.entity.CustomerProfile;
import com.raaspal.robotrecommendation.customer.repository.CustomerProfileRepository;
import jakarta.mail.internet.MimeMessage;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.mail.javamail.MimeMessageHelper;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.UUID;

/**
 * Sends a free-text announcement email to selected customers. The email body is
 * exactly the admin's message — no report links, templates, or extra content —
 * so this channel is safe for general communication separate from reports.
 * Each customer is a separate email (recipients don't see each other); optional
 * CC addresses are added to every one.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class CustomerAnnouncementService {

    private final CustomerProfileRepository customerProfileRepository;
    private final JavaMailSender mailSender;

    @Value("${app.mail.from}")
    private String from;

    public AnnouncementResult send(AnnouncementRequest request) {
        List<String> cc = parseAddresses(request.cc() == null ? "" : String.join(",", request.cc()));

        List<AnnouncementResult.Item> items = new ArrayList<>();
        int sent = 0;
        int failed = 0;

        for (UUID customerId : request.customerProfileIds()) {
            CustomerProfile customer = customerProfileRepository.findById(customerId).orElse(null);
            if (customer == null) {
                items.add(new AnnouncementResult.Item("(unknown)", "", false, "Customer not found"));
                failed++;
                continue;
            }
            String name = customer.getCompanyName();
            List<String> to = parseAddresses(customer.getContactEmail());
            if (to.isEmpty()) {
                items.add(new AnnouncementResult.Item(name, "", false, "No contact email"));
                failed++;
                continue;
            }
            try {
                deliver(to, cc, request.subject(), request.message());
                items.add(new AnnouncementResult.Item(name, String.join(", ", to), true, null));
                sent++;
            } catch (Exception e) {
                log.error("Announcement send failed for {}: {}", name, e.getMessage(), e);
                items.add(new AnnouncementResult.Item(name, String.join(", ", to), false, e.getMessage()));
                failed++;
            }
        }

        if (sent == 0 && failed > 0) {
            // Nothing got out — surface a clear error (e.g. SMTP misconfigured).
            throw new BadRequestException("No emails were sent. First error: "
                    + items.stream().filter(i -> !i.ok()).findFirst().map(AnnouncementResult.Item::error).orElse("unknown"));
        }
        log.info("Announcement sent: {} ok, {} failed", sent, failed);
        return new AnnouncementResult(sent, failed, items);
    }

    /** Sends the message verbatim as a plain-text email — no HTML, no added content. */
    private void deliver(List<String> to, List<String> cc, String subject, String message) throws Exception {
        MimeMessage mime = mailSender.createMimeMessage();
        MimeMessageHelper helper = new MimeMessageHelper(mime, false, "UTF-8");
        helper.setFrom(from);
        helper.setTo(to.toArray(new String[0]));
        if (!cc.isEmpty()) {
            helper.setCc(cc.toArray(new String[0]));
        }
        helper.setSubject(subject);
        helper.setText(message, false); // false = plain text, exactly as typed
        mailSender.send(mime);
    }

    private List<String> parseAddresses(String raw) {
        if (raw == null || raw.isBlank()) {
            return List.of();
        }
        return Arrays.stream(raw.split("[,;]"))
                .map(String::trim)
                .filter(s -> !s.isBlank())
                .distinct()
                .toList();
    }
}
