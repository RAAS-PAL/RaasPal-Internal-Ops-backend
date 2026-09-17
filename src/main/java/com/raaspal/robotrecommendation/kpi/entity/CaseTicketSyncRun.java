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

import java.time.LocalDateTime;
import java.util.UUID;

/**
 * One sync of one board: when it ran, who started it, how many tickets it
 * touched, and — when it failed — the monday error, so a dashboard showing
 * stale numbers can be traced to a bad token or a hidden board.
 */
@Entity
@Table(name = "kpi_case_ticket_sync_run")
@Getter
@Setter
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class CaseTicketSyncRun {

    public enum Status { RUNNING, SUCCEEDED, FAILED }

    public enum Trigger { SCHEDULED, MANUAL }

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @Column(name = "source_board_id", nullable = false, columnDefinition = "TEXT")
    private String sourceBoardId;

    /** Null when the board carries both lines and names the deciding column instead. */
    @Enumerated(EnumType.STRING)
    @Column(name = "service_line", length = 16)
    private ServiceLine serviceLine;

    /** Always known: it is a property of the board, not of its rows. */
    @Enumerated(EnumType.STRING)
    @Column(name = "ticket_type", nullable = false, length = 16)
    private TicketType ticketType;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 16)
    private Status status;

    @Enumerated(EnumType.STRING)
    @Column(name = "triggered_by", nullable = false, length = 16)
    private Trigger triggeredBy;

    @Column(name = "started_at", nullable = false)
    private LocalDateTime startedAt;

    @Column(name = "finished_at")
    private LocalDateTime finishedAt;

    @Column(name = "groups_read", nullable = false)
    private int groupsRead;

    @Column(name = "items_read", nullable = false)
    private int itemsRead;

    @Column(name = "items_inserted", nullable = false)
    private int itemsInserted;

    @Column(name = "items_updated", nullable = false)
    private int itemsUpdated;

    @Column(name = "items_unchanged", nullable = false)
    private int itemsUnchanged;

    @Column(name = "items_marked_absent", nullable = false)
    private int itemsMarkedAbsent;

    @Column(name = "error_message", columnDefinition = "TEXT")
    private String errorMessage;
}
