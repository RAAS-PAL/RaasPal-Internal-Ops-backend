package com.raaspal.robotrecommendation.knowledge;

import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.autoconfigure.mail.MailSenderAutoConfiguration;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.core.io.support.PropertiesLoaderUtils;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.mail.javamail.JavaMailSenderImpl;
import org.springframework.test.util.ReflectionTestUtils;

import static org.assertj.core.api.Assertions.*;

class KcMailConfigurationTest {
    @Test void kcSenderCopiesSmtpCredentialsWithoutReplacingOrMutatingTheSharedSender() {
        // Read the production timeout settings themselves; test/resources shadows
        // the regular application.properties, so don't accidentally test a fixture.
        var resource = new org.springframework.core.io.FileSystemResource("src/main/resources/application.properties");
        java.util.Properties production;
        try { production = PropertiesLoaderUtils.loadProperties(resource); }
        catch (java.io.IOException ex) { throw new java.io.UncheckedIOException(ex); }

        var context = new ApplicationContextRunner()
                .withConfiguration(AutoConfigurations.of(MailSenderAutoConfiguration.class))
                .withUserConfiguration(KcMailConfiguration.class)
                .withPropertyValues("spring.mail.host=smtp.example.invalid", "spring.mail.port=587",
                        "spring.mail.username=test-mail-user", "spring.mail.password=test-mail-password",
                        "spring.mail.properties.mail.smtp.auth=true",
                        "spring.mail.properties.mail.smtp.starttls.enable=true",
                        "spring.mail.properties.mail.smtp.ssl.checkserveridentity=true",
                        "app.mail.from=Reports <no-reply@raaspal.com>");
        for (String key : new String[]{"connectiontimeout", "timeout", "writetimeout"}) {
            String property = "spring.mail.properties.mail.smtp." + key;
            assertThat(production.getProperty(property)).isEqualTo("60000");
            context = context.withPropertyValues(property + "=" + production.getProperty(property));
        }
        context.run(ctx -> {
            assertThat(ctx).hasNotFailed().hasSingleBean(JavaMailSender.class).hasSingleBean(SmtpKcCodeMailer.class);
            JavaMailSenderImpl shared = ctx.getBean(JavaMailSenderImpl.class);
            JavaMailSenderImpl code = (JavaMailSenderImpl) ReflectionTestUtils.getField(ctx.getBean(SmtpKcCodeMailer.class), "sender");
            assertThat(code).isNotSameAs(shared);
            assertThat(code.getHost()).isEqualTo(shared.getHost());
            assertThat(code.getPort()).isEqualTo(shared.getPort());
            assertThat(code.getUsername()).isEqualTo(shared.getUsername());
            assertThat(code.getPassword().equals(shared.getPassword())).isTrue();
            assertThat(code.getJavaMailProperties()).containsEntry("mail.smtp.auth", "true")
                    .containsEntry("mail.smtp.starttls.enable", "true")
                    .containsEntry("mail.smtp.ssl.checkserveridentity", "true")
                    .containsEntry("mail.smtp.connectiontimeout", "5000")
                    .containsEntry("mail.smtp.timeout", "10000")
                    .containsEntry("mail.smtp.writetimeout", "10000");
            assertThat(shared.getJavaMailProperties()).containsEntry("mail.smtp.connectiontimeout", "60000")
                    .containsEntry("mail.smtp.timeout", "60000")
                    .containsEntry("mail.smtp.writetimeout", "60000");
        });
    }
}
