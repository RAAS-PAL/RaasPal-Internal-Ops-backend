package com.raaspal.robotrecommendation.knowledge;

import jakarta.mail.MessagingException;
import jakarta.mail.internet.InternetAddress;
import java.io.UnsupportedEncodingException;
import org.springframework.mail.MailPreparationException;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.mail.javamail.MimeMessageHelper;

public class SmtpKcCodeMailer implements KcCodeMailer {
    private final JavaMailSender sender;
    private final String from;

    public SmtpKcCodeMailer(JavaMailSender sender, String from) {
        this.sender = sender;
        this.from = from;
    }

    @Override
    public void send(String email, EmailCode.Purpose purpose, String code) {
        try {
            var message = sender.createMimeMessage();
            var helper = new MimeMessageHelper(message, false, "UTF-8");
            helper.setFrom(new InternetAddress(from).getAddress(), "RAASPAL Knowledge Center");
            helper.setTo(email);
            helper.setSubject("RAAS PAL Knowledge Center — รหัสยืนยัน / Verification code");
            String action = purpose == EmailCode.Purpose.SIGNUP
                    ? "สร้างบัญชี / Create account" : "ตั้งรหัสผ่านใหม่ / Reset password";
            helper.setText(action + "\n\n"
                    + "รหัสยืนยัน / Verification code: " + code + "\n"
                    + "รหัสนี้ใช้ได้ 10 นาที / This code is valid for 10 minutes.\n\n"
                    + "หากคุณไม่ได้ส่งคำขอนี้ โปรดละเว้นอีเมลนี้\n"
                    + "Ignore this email if you did not request it.\n", false);
            // Deliberately no shared reporting CC and no body/recipient logging.
            sender.send(message);
        } catch (MessagingException | UnsupportedEncodingException ex) {
            throw new MailPreparationException("unavailable");
        }
    }
}
