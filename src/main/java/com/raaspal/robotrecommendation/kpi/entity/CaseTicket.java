package com.raaspal.robotrecommendation.kpi.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
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
import java.time.LocalDateTime;
import java.util.UUID;

/**
 * One service ticket mirrored from a monday.com board — the current state of
 * it, updated in place on every sync. This is what the RE KPI dashboard counts
 * CM cases, SLA and first-time fix from.
 *
 * <p>Nothing here is normalised: branch names keep the board's typos and
 * serials keep the board's casing, except {@link #serialsNormalised}, which
 * exists only so repeat detection can join on it. Every column the board
 * returned is kept verbatim in {@link #rawColumns}, so a field that is mapped
 * later is already here for history.
 *
 * <p>Table 1 of the parked V34 case-report design plus the KPI columns; see
 * {@code V45__add_case_ticket_sync.sql}.
 */
@Entity
@Table(name = "case_ticket")
@Getter
@Setter
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class CaseTicket {

    /** The only source today. Named so a spreadsheet import can sit beside it. */
    public static final String SOURCE_MONDAY = "MONDAY";

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @Column(name = "source", nullable = false, length = 20)
    private String source;

    @Column(name = "source_board_id", nullable = false, columnDefinition = "TEXT")
    private String sourceBoardId;

    @Column(name = "source_item_id", nullable = false, columnDefinition = "TEXT")
    private String sourceItemId;

    @Column(name = "source_group_id", columnDefinition = "TEXT")
    private String sourceGroupId;

    @Column(name = "source_group_title", columnDefinition = "TEXT")
    private String sourceGroupTitle;

    /**
     * Null when the source board does not say which kind of robot the ticket is
     * about and the model did not resolve it. Such rows count in fleet totals but
     * are left out of the cleaning/delivery split rather than guessed into one.
     */
    @Enumerated(EnumType.STRING)
    @Column(name = "service_line", length = 16)
    private ServiceLine serviceLine;

    /** Which board family this came from: an installation job, or a corrective-maintenance case. */
    @Enumerated(EnumType.STRING)
    @Column(name = "ticket_type", nullable = false, length = 16)
    private TicketType ticketType;

    @Column(name = "item_name", columnDefinition = "TEXT")
    private String itemName;

    @Column(name = "ticket_no", columnDefinition = "TEXT")
    private String ticketNo;

    /** Raw, as the board holds it; may name several robots. */
    @Column(name = "serial_numbers", columnDefinition = "TEXT")
    private String serialNumbers;

    /** Upper-cased, whitespace-stripped, {@code |}-joined. What repeat detection joins on. */
    @Column(name = "serials_normalised", columnDefinition = "TEXT")
    private String serialsNormalised;

    @Column(name = "project_raw", columnDefinition = "TEXT")
    private String projectRaw;

    @Column(name = "branch_raw", columnDefinition = "TEXT")
    private String branchRaw;

    @Column(name = "branch_code_raw", columnDefinition = "TEXT")
    private String branchCodeRaw;

    /** Drives the SLA threshold: 3 days in greater Bangkok, 5 upcountry (Delivery). */
    @Column(name = "province_raw", columnDefinition = "TEXT")
    private String provinceRaw;

    @Column(name = "robot_model", columnDefinition = "TEXT")
    private String robotModel;

    @Column(name = "status", columnDefinition = "TEXT")
    private String status;

    @Column(name = "sup_status", columnDefinition = "TEXT")
    private String supStatus;

    @Column(name = "issue_level", columnDefinition = "TEXT")
    private String issueLevel;

    @Column(name = "main_issue", columnDefinition = "TEXT")
    private String mainIssue;

    /**
     * The board's category column, verbatim ("Job Type" on the installation board,
     * "Type of case" on the CM boards). Which values count toward the KPI is board
     * config; rows outside it are archived but not counted, and reported.
     */
    @Column(name = "category", columnDefinition = "TEXT")
    private String category;

    @Column(name = "open_date")
    private LocalDate openDate;

    @Column(name = "close_date")
    private LocalDate closeDate;

    /**
     * The board's "RE Action" date — when the team first acted. This is what SLA
     * is measured against ({@code action_date - open_date}), not the close date,
     * because neither ticket board carries a close date at all.
     */
    @Column(name = "action_date")
    private LocalDate actionDate;

    /** Installation tickets only: the later end of the TimeLine column. */
    @Column(name = "install_date")
    private LocalDate installDate;

    /** Close date set, or status listed as finished in the board config. */
    @Column(name = "is_closed", nullable = false)
    private boolean closed;

    /** JSON array of {@code {id, title, type, text}} — every column the board returned. */
    @Column(name = "raw_columns", columnDefinition = "TEXT")
    private String rawColumns;

    /** monday's {@code updated_at}, in UTC. A sync counts a row as updated only when this moved. */
    @Column(name = "source_updated_at")
    private LocalDateTime sourceUpdatedAt;

    @Column(name = "first_seen_at", nullable = false)
    private LocalDateTime firstSeenAt;

    @Column(name = "last_synced_at", nullable = false)
    private LocalDateTime lastSyncedAt;

    /** False once the board no longer returns the item. Never deleted — past months must not move. */
    @Column(name = "is_present", nullable = false)
    private boolean present;
}
