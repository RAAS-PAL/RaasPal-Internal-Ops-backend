package com.raaspal.robotrecommendation.report.delivery;

/** Thrown when POSTing a monthly report payload to the n8n webhook fails. */
public class N8nDeliveryException extends RuntimeException {

    public N8nDeliveryException(String message, Throwable cause) {
        super(message, cause);
    }
}
