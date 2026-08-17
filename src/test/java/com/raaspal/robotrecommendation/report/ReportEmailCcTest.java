package com.raaspal.robotrecommendation.report;

import com.raaspal.robotrecommendation.customer.entity.CustomerProfile;
import com.raaspal.robotrecommendation.customer.repository.CustomerProfileRepository;
import com.raaspal.robotrecommendation.report.service.ReportEmailService;
import jakarta.mail.Message;
import jakarta.mail.internet.MimeMessage;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.mail.javamail.JavaMailSenderImpl;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.transaction.annotation.Transactional;

import java.util.Arrays;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * The standing CC on monthly report emails — the RAASPAL team addresses that keep
 * a copy of what customers receive, configured with {@code MAIL_CC}.
 *
 * <p>{@code management.health.mail.enabled=false} is required: Actuator's mail
 * health contributor looks for a concrete {@code JavaMailSenderImpl}, and mocking
 * the {@code JavaMailSender} leaves it with none, which fails the whole context.
 */
@SpringBootTest
@Transactional
@TestPropertySource(properties = {
        "app.public.base-url=https://example.test",
        "management.health.mail.enabled=false",
})
class ReportEmailCcTest {

    @Autowired private ReportEmailService reportEmailService;
    @Autowired private CustomerProfileRepository customerProfileRepository;

    @MockitoBean private JavaMailSender mailSender;

    private CustomerProfile customer;

    @BeforeEach
    void setUp() {
        // A real MimeMessage, so the helper's own header handling is exercised
        // rather than stubbed away.
        when(mailSender.createMimeMessage()).thenReturn(new JavaMailSenderImpl().createMimeMessage());
        customer = customerProfileRepository.save(CustomerProfile.builder()
                .companyName("CC Test Co")
                .contactEmail("ops@customer.test")
                .build());
    }

    /**
     * Sets the CC the way {@code MAIL_CC} would, without standing up a second
     * application context per variation — the value is {@code @Value}-injected, so
     * a per-class property source would mean a fresh context for every case.
     */
    private void configureCc(String value) {
        ReflectionTestUtils.setField(reportEmailService, "cc", value);
    }

    private MimeMessage captureSentMessage() {
        ArgumentCaptor<MimeMessage> captor = ArgumentCaptor.forClass(MimeMessage.class);
        verify(mailSender).send(captor.capture());
        return captor.getValue();
    }

    private static List<String> addresses(MimeMessage message, Message.RecipientType type) throws Exception {
        var recipients = message.getRecipients(type);
        return recipients == null ? List.of() : Arrays.stream(recipients).map(Object::toString).toList();
    }

    /** The team gets a copy of every report the customer receives. */
    @Test
    void theConfiguredTeamAddressesAreCcdOnTheReportEmail() throws Exception {
        configureCc("team@raaspal.com, ops@raaspal.com");

        reportEmailService.sendBundle(customer.getId(), "2026-07");

        MimeMessage sent = captureSentMessage();
        assertThat(addresses(sent, Message.RecipientType.TO)).containsExactly("ops@customer.test");
        assertThat(addresses(sent, Message.RecipientType.CC))
                .containsExactlyInAnyOrder("team@raaspal.com", "ops@raaspal.com");
    }

    /** Semicolons are accepted too, matching how contact emails are already entered. */
    @Test
    void semicolonSeparatedAddressesAreAccepted() throws Exception {
        configureCc("team@raaspal.com; ops@raaspal.com");

        reportEmailService.sendBundle(customer.getId(), "2026-07");

        assertThat(addresses(captureSentMessage(), Message.RecipientType.CC))
                .containsExactlyInAnyOrder("team@raaspal.com", "ops@raaspal.com");
    }

    /**
     * A colleague who is also listed as a customer contact must not appear in both
     * headers — they would get the mail twice, and the customer would see what looks
     * like a mistake on a document we sent them.
     */
    @Test
    void anAddressAlreadyOnTheToLineIsNotAlsoCcd() throws Exception {
        customer.setContactEmail("ops@customer.test, TEAM@raaspal.com");
        customerProfileRepository.save(customer);
        configureCc("team@raaspal.com, ops@raaspal.com");

        reportEmailService.sendBundle(customer.getId(), "2026-07");

        MimeMessage sent = captureSentMessage();
        assertThat(addresses(sent, Message.RecipientType.TO))
                .containsExactlyInAnyOrder("ops@customer.test", "TEAM@raaspal.com");
        assertThat(addresses(sent, Message.RecipientType.CC))
                .as("the duplicate is dropped, case-insensitively")
                .containsExactly("ops@raaspal.com");
    }

    /** The default state for anyone who has not set MAIL_CC — sending must still work. */
    @Test
    void anEmptyCcSettingSendsWithNoCcHeader() throws Exception {
        configureCc("");

        reportEmailService.sendBundle(customer.getId(), "2026-07");

        assertThat(captureSentMessage().getRecipients(Message.RecipientType.CC)).isNull();
    }

    /** A stray trailing separator must not become a blank, invalid recipient. */
    @Test
    void blankEntriesInTheCcListAreIgnored() throws Exception {
        configureCc("team@raaspal.com, , ;");

        reportEmailService.sendBundle(customer.getId(), "2026-07");

        assertThat(addresses(captureSentMessage(), Message.RecipientType.CC))
                .containsExactly("team@raaspal.com");
    }

    /**
     * The RAAS PAL banner must arrive as a {@code multipart/related} inline part whose
     * Content-ID matches the {@code cid:} in the body. If the two drift apart the
     * image silently becomes a file attachment and the mail ends with a broken
     * placeholder — which no test asserting "an email was sent" would catch.
     */
    @Test
    void theFooterBannerIsCarriedInlineAndReferencedByTheBody() throws Exception {
        configureCc("");

        reportEmailService.sendBundle(customer.getId(), "2026-07");
        MimeMessage sent = captureSentMessage();
        // Transport.send() normally does this; the mocked sender never does, so the
        // Content-Type header would still read the default text/plain.
        sent.saveChanges();

        assertThat(sent.getContentType()).contains("multipart/");

        String cid = null;
        String bodyHtml = null;
        var stack = new java.util.ArrayDeque<Object>();
        stack.push(sent.getContent());
        while (!stack.isEmpty()) {
            Object part = stack.pop();
            if (part instanceof jakarta.mail.Multipart multipart) {
                for (int i = 0; i < multipart.getCount(); i++) {
                    jakarta.mail.BodyPart bodyPart = multipart.getBodyPart(i);
                    String[] ids = bodyPart.getHeader("Content-ID");
                    if (ids != null && ids.length > 0) cid = ids[0].replaceAll("[<>]", "");
                    if (bodyPart.isMimeType("text/html")) bodyHtml = bodyPart.getContent().toString();
                    if (bodyPart.isMimeType("multipart/*")) stack.push(bodyPart.getContent());
                }
            }
        }

        assertThat(cid).as("an inline part with a Content-ID must be present").isEqualTo("raaspalFooterBanner");
        assertThat(bodyHtml).as("the body must reference that exact CID").contains("cid:raaspalFooterBanner");
    }
}
