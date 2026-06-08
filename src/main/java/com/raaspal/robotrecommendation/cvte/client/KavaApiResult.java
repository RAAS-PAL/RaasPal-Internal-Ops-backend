package com.raaspal.robotrecommendation.cvte.client;

/**
 * Pairs the parsed payload with the Kava response's {@code code}/{@code msg},
 * so callers can persist a human-readable "last API message" alongside the data.
 */
public record KavaApiResult<T>(T data, String code, String message) {

    public String summary() {
        if (code == null && message == null) {
            return null;
        }
        if (code == null) {
            return message;
        }
        if (message == null) {
            return code;
        }
        return code + ": " + message;
    }
}
