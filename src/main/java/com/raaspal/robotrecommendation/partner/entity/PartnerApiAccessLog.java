package com.raaspal.robotrecommendation.partner.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.LocalDateTime;
import java.util.UUID;

/**
 * One recorded partner-API request. Broader than
 * {@link PartnerApiKey#getLastUsedAt()} (a single "most recent" stamp): these
 * rows answer <em>who fetched what, when</em> — for reviewing a partner's usage,
 * settling disputes, and spotting abuse.
 *
 * <p>{@code partnerId}/{@code apiKeyId} are null when authentication failed,
 * which is intentional — rejected attempts are worth recording too.
 */
@Entity
@Table(name = "partner_api_access_logs")
@Getter
@Setter
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class PartnerApiAccessLog {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    /** Null when the request did not authenticate. */
    @Column(name = "partner_id")
    private UUID partnerId;

    /** Null when the request did not authenticate. */
    @Column(name = "api_key_id")
    private UUID apiKeyId;

    @Column(nullable = false, length = 10)
    private String method;

    @Column(nullable = false, length = 500)
    private String path;

    @Column(name = "query_string", length = 1000)
    private String queryString;

    @Column(nullable = false)
    private Integer status;

    @Column(name = "duration_ms", nullable = false)
    private Integer durationMs;

    @Column(name = "client_ip", length = 64)
    private String clientIp;

    @Column(name = "requested_at", nullable = false)
    private LocalDateTime requestedAt;
}
