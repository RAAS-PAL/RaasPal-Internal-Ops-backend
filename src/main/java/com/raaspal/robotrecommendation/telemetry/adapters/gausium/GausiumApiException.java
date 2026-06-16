package com.raaspal.robotrecommendation.telemetry.adapters.gausium;

public class GausiumApiException extends RuntimeException {

    public GausiumApiException(String message) {
        super(message);
    }

    public GausiumApiException(String message, Throwable cause) {
        super(message, cause);
    }
}
