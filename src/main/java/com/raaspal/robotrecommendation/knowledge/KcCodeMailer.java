package com.raaspal.robotrecommendation.knowledge;

/** Implementations must not log the code or the message body. */
public interface KcCodeMailer {
    void send(String email, EmailCode.Purpose purpose, String code);
}
