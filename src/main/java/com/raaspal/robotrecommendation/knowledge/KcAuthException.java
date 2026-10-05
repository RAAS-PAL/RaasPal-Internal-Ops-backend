package com.raaspal.robotrecommendation.knowledge;

import lombok.Getter;

/** Only translation codes, never submitted secrets or SMTP error details. */
@Getter
public class KcAuthException extends RuntimeException {
    private final int status;
    private final long retryAfter;

    public KcAuthException(String code) { this(code, 400, 0); }
    public KcAuthException(String code, int status, long retryAfter) {
        super(code);
        this.status = status;
        this.retryAfter = retryAfter;
    }
}
