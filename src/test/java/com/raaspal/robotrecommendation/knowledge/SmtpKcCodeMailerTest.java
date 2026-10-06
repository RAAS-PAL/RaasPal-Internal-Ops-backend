package com.raaspal.robotrecommendation.knowledge;

import jakarta.mail.*;
import jakarta.mail.internet.MimeMessage;
import jakarta.mail.internet.InternetAddress;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.springframework.mail.javamail.JavaMailSender;
import java.util.Properties;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

class SmtpKcCodeMailerTest {
    @ParameterizedTest @EnumSource(EmailCode.Purpose.class)
    void sendsPlainUtf8EmailInBothLanguagesToOnlyTheRequester(EmailCode.Purpose purpose) throws Exception {
        JavaMailSender sender = mock(JavaMailSender.class);
        MimeMessage message = new MimeMessage(Session.getInstance(new Properties()));
        when(sender.createMimeMessage()).thenReturn(message);
        new SmtpKcCodeMailer(sender, "RAAS PAL <no-reply@raaspal.com>")
                .send("kc-mail-test@raaspal.com", purpose, "123456");
        message.saveChanges();
        verify(sender).send(message);
        assertThat(message.getSubject()).contains("รหัสยืนยัน", "Verification code");
        InternetAddress from = (InternetAddress) message.getFrom()[0];
        assertThat(from.getAddress()).isEqualTo("no-reply@raaspal.com");
        assertThat(from.getPersonal()).isEqualTo("RAASPAL Knowledge Center");
        assertThat(message.getContentType()).startsWith("text/plain").contains("UTF-8");
        String body = message.getContent().toString();
        assertThat(body.contains("123456")).isTrue();
        assertThat(body).contains("10 นาที", "10 minutes", "โปรดละเว้น", "Ignore this email");
        assertThat(message.getAllRecipients()).hasSize(1);
        assertThat(message.getRecipients(Message.RecipientType.CC)).isNull();
        assertThat(message.getRecipients(Message.RecipientType.BCC)).isNull();
        assertThat(body).contains(purpose == EmailCode.Purpose.SIGNUP ? "สร้างบัญชี / Create account" : "ตั้งรหัสผ่านใหม่ / Reset password");
    }
}
