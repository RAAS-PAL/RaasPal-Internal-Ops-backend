package com.raaspal.robotrecommendation.reassignment.entity;

import jakarta.persistence.*;
import lombok.*;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

/**
 * An approved assignment of an engineer to a ticket. Maps to {@code re_assignment} (V53).
 *
 * <p>Life cycle: APPROVED (waiting for the engineer to appear in the board's RE column)
 * → CONFIRMED (seen there by a refresh). CANCELLED = withdrawn in the console;
 * SUPERSEDED = monday shows somebody else. At most one current row (ended_at null) per ticket.
 */
@Entity
@Table(name = "re_assignment")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class ReAssignment {

    public static final String APPROVED = "APPROVED";
    public static final String CONFIRMED = "CONFIRMED";
    public static final String CANCELLED = "CANCELLED";
    public static final String SUPERSEDED = "SUPERSEDED";

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @Column(name = "board_id", nullable = false, columnDefinition = "TEXT")
    private String boardId;

    @Column(name = "item_id", nullable = false, columnDefinition = "TEXT")
    private String itemId;

    @Column(name = "engineer_id", nullable = false)
    private UUID engineerId;

    @Column(nullable = false, columnDefinition = "TEXT")
    private String status;

    /** SUGGESTION | ALTERNATIVE | MANUAL */
    @Column(nullable = false, columnDefinition = "TEXT")
    private String origin;

    @Column(precision = 7, scale = 2)
    private BigDecimal score;

    @Column(name = "required_level")
    private Short requiredLevel;

    @Column(nullable = false, columnDefinition = "TEXT")
    private String reason;

    /** The decision as the approver saw it: levels, load, every candidate and exclusion. */
    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "decision_snapshot", nullable = false)
    private String decisionSnapshot;

    @Column(name = "approved_by", nullable = false, columnDefinition = "TEXT")
    private String approvedBy;

    @Column(name = "approved_at", nullable = false)
    @Builder.Default
    private Instant approvedAt = Instant.now();

    @Column(name = "confirmed_at")
    private Instant confirmedAt;

    @Column(name = "ended_at")
    private Instant endedAt;

    @Column(name = "ended_by", columnDefinition = "TEXT")
    private String endedBy;

    /** NOT_SENT | SENT | FAILED | DISABLED | NO_ADDRESS */
    @Column(name = "email_status", nullable = false, columnDefinition = "TEXT")
    @Builder.Default
    private String emailStatus = "NOT_SENT";

    @Column(name = "email_detail", columnDefinition = "TEXT")
    private String emailDetail;

    @Column(name = "email_sent_at")
    private Instant emailSentAt;

    /** NOT_WRITTEN | WRITTEN | CLEARED | LEFT - what happened in the board's RE column (V54). */
    @Column(name = "monday_status", nullable = false, columnDefinition = "TEXT")
    @Builder.Default
    private String mondayStatus = MONDAY_NOT_WRITTEN;

    @Column(name = "monday_detail", columnDefinition = "TEXT")
    private String mondayDetail;

    @Column(name = "monday_written_at")
    private Instant mondayWrittenAt;

    public static final String MONDAY_NOT_WRITTEN = "NOT_WRITTEN";
    public static final String MONDAY_WRITTEN = "WRITTEN";
    public static final String MONDAY_CLEARED = "CLEARED";
    public static final String MONDAY_LEFT = "LEFT";

    public boolean isCurrent() {
        return endedAt == null;
    }
}
