package com.raaspal.robotrecommendation.partner.dto;

import com.raaspal.robotrecommendation.partner.entity.PartnerApiAccessLog;

import java.time.LocalDateTime;
import java.util.UUID;

/** One recorded partner-API request, as shown in the admin console. */
public record AccessLogResponse(
        UUID id,
        UUID apiKeyId,
        String method,
        String path,
        String queryString,
        int status,
        int durationMs,
        String clientIp,
        LocalDateTime requestedAt) {

    public static AccessLogResponse of(PartnerApiAccessLog log) {
        return new AccessLogResponse(
                log.getId(),
                log.getApiKeyId(),
                log.getMethod(),
                log.getPath(),
                log.getQueryString(),
                log.getStatus(),
                log.getDurationMs(),
                log.getClientIp(),
                log.getRequestedAt());
    }
}
