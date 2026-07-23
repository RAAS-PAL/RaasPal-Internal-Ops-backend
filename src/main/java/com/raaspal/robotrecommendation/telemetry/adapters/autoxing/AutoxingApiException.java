package com.raaspal.robotrecommendation.telemetry.adapters.autoxing;

/**
 * Raised when a call to the AutoXing Cloud Platform API fails. {@code authFailure}
 * flags the documented 400 "Authentication failed" case (or an invalid/expired
 * token) so callers can re-authenticate and retry once.
 */
public class AutoxingApiException extends RuntimeException {

    private final boolean authFailure;

    public AutoxingApiException(String message) {
        this(message, false);
    }

    public AutoxingApiException(String message, boolean authFailure) {
        super(message);
        this.authFailure = authFailure;
    }

    public AutoxingApiException(String message, Throwable cause) {
        super(message, cause);
        this.authFailure = false;
    }

    public boolean isAuthFailure() {
        return authFailure;
    }
}
