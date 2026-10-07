package com.raaspal.robotrecommendation.casereport.share;

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

import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.UUID;

/** A public link to pending cases (V69): a details page, a tab for a period, or one case. The token is the access. */
@Entity
@Table(name = "case_share_links")
@Getter
@Setter
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class CaseShareLink {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @Column(nullable = false, unique = true, length = 64)
    private String token;

    /** SHEET, VIEW or CASE: a details page, a tab for a period, or one case. */
    @Column(nullable = false, length = 8)
    private String kind;

    /** SHEET: the details page's slug. CASE: the case's sheet, or aotga. */
    @Column(length = 16)
    private String sheet;

    /** VIEW: the tab - internal, pcs, makro, ifs, mk, on-hold or aot. */
    @Column(name = "view_name", length = 16)
    private String view;

    /** VIEW on a tab of both boards: BOTH, CLEANING or DELIVERY. */
    @Column(length = 10)
    private String scope;

    /** JSON array of customer names; null for every case. */
    @Column(columnDefinition = "TEXT")
    private String customers;

    /** VIEW: DAILY, WEEKLY, MONTHLY or ALL, as picked. */
    @Column(length = 8)
    private String cadence;

    /** VIEW: the period's first day; null for all time. */
    @Column(name = "period_from")
    private LocalDate periodFrom;

    @Column(name = "period_to")
    private LocalDate periodTo;

    /** CASE: the monday item id, or the AOT ticket number. */
    @Column(name = "case_key", length = 64)
    private String caseKey;

    @Column(name = "created_by")
    private UUID createdBy;

    @Column(name = "created_at", nullable = false)
    private OffsetDateTime createdAt;

    @Column(name = "expires_at", nullable = false)
    private OffsetDateTime expiresAt;

    @Column(name = "revoked_at")
    private OffsetDateTime revokedAt;

    @Column(name = "view_count", nullable = false)
    private int viewCount;

    @Column(name = "last_viewed_at")
    private OffsetDateTime lastViewedAt;
}
