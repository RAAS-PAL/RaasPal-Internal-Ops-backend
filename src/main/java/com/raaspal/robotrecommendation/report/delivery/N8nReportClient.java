package com.raaspal.robotrecommendation.report.delivery;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientResponseException;

/**
 * POSTs a customer's monthly report payload to the configured n8n webhook,
 * which performs the actual LINE Messaging API push. Inert until
 * {@code app.reports.n8n.webhook-url} is configured.
 */
@Slf4j
@Component
public class N8nReportClient {

    private final RestClient restClient;
    private final String webhookUrl;

    public N8nReportClient(@Value("${app.reports.n8n.webhook-url:}") String webhookUrl) {
        this.webhookUrl = webhookUrl;
        this.restClient = RestClient.builder().build();
    }

    /** Whether the n8n webhook URL is configured. */
    public boolean isConfigured() {
        return !webhookUrl.isBlank();
    }

    /** Sends one customer's monthly report payload to n8n. Throws on failure. */
    public void send(MonthlyReportPayload payload) {
        try {
            restClient.post()
                    .uri(webhookUrl)
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(payload)
                    .retrieve()
                    .toBodilessEntity();
        } catch (RestClientResponseException e) {
            throw new N8nDeliveryException(
                    "n8n webhook rejected payload for customer " + payload.customerId() + ": "
                            + e.getStatusCode() + " " + e.getResponseBodyAsString(), e);
        } catch (Exception e) {
            throw new N8nDeliveryException(
                    "Failed to POST to n8n webhook for customer " + payload.customerId() + ": " + e.getMessage(), e);
        }
    }
}
