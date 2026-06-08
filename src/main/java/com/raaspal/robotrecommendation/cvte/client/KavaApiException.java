package com.raaspal.robotrecommendation.cvte.client;

/** Wraps any failure talking to the Kava Open Gateway API (network, auth, parsing). */
public class KavaApiException extends RuntimeException {

    public KavaApiException(String message) {
        super(message);
    }

    public KavaApiException(String message, Throwable cause) {
        super(message, cause);
    }
}
