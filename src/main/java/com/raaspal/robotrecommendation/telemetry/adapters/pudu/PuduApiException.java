package com.raaspal.robotrecommendation.telemetry.adapters.pudu;

/**
 * Raised when a call to the PUDU Open Platform fails. {@code authFailure} flags a
 * gateway rejection of the signature or key (401), which no retry will fix — the
 * credentials or the signing are wrong, and the caller should say so rather than try
 * again.
 */
public class PuduApiException extends RuntimeException {

    private final boolean authFailure;

    public PuduApiException(String message) {
        this(message, false);
    }

    public PuduApiException(String message, boolean authFailure) {
        super(message);
        this.authFailure = authFailure;
    }

    public PuduApiException(String message, Throwable cause) {
        super(message, cause);
        this.authFailure = false;
    }

    public boolean isAuthFailure() {
        return authFailure;
    }
}
