package com.raaspal.robotrecommendation.knowledge;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.mail.MailProperties;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.mail.javamail.JavaMailSenderImpl;

import java.util.Properties;

@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(MailProperties.class)
public class KcMailConfiguration {
    @Bean
    public SmtpKcCodeMailer kcCodeMailer(MailProperties properties, @Value("${app.mail.from}") String from) {
        return new SmtpKcCodeMailer(codeSender(properties), from);
    }

    // Keep this sender private to KC, not a JavaMailSender bean: adding another such
    // bean disables Boot's shared mail auto-configuration and can reroute reports.
    static JavaMailSenderImpl codeSender(MailProperties properties) {
        JavaMailSenderImpl sender = new JavaMailSenderImpl();
        sender.setHost(properties.getHost());
        if (properties.getPort() != null) sender.setPort(properties.getPort());
        sender.setProtocol(properties.getProtocol());
        sender.setUsername(properties.getUsername());
        sender.setPassword(properties.getPassword());
        sender.setDefaultEncoding(properties.getDefaultEncoding().name());
        Properties smtp = new Properties();
        smtp.putAll(properties.getProperties());
        smtp.setProperty("mail.smtp.connectiontimeout", "5000");
        smtp.setProperty("mail.smtp.timeout", "10000");
        smtp.setProperty("mail.smtp.writetimeout", "10000");
        smtp.setProperty("mail.debug", "false");
        smtp.setProperty("mail.debug.auth", "false");
        sender.setJavaMailProperties(smtp);
        return sender;
    }
}
