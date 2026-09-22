package com.raaspal.robotrecommendation.reassignment.entity;

import jakarta.persistence.*;
import lombok.*;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.io.Serializable;
import java.time.Instant;
import java.time.LocalDate;

/**
 * An open ticket as last read from monday, with only what assignment needs. Maps to
 * {@code re_ticket} (V53).
 *
 * <p>Separate from {@code case_ticket} on purpose: the pending-case reports read
 * {@code case_ticket.is_present} as "in the All Case group", and this refresh reads every
 * group. {@code open} turns false when a refresh no longer returns the ticket.
 */
@Entity
@Table(name = "re_ticket")
@IdClass(ReTicket.Key.class)
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class ReTicket {

    @Id
    @Column(name = "board_id", columnDefinition = "TEXT")
    private String boardId;

    @Id
    @Column(name = "item_id", columnDefinition = "TEXT")
    private String itemId;

    @Column(name = "item_name", columnDefinition = "TEXT")
    private String itemName;

    @Column(name = "group_title", columnDefinition = "TEXT")
    private String groupTitle;

    @Column(columnDefinition = "TEXT")
    private String status;

    @Column(name = "sub_status", columnDefinition = "TEXT")
    private String subStatus;

    @Column(name = "model_label", columnDefinition = "TEXT")
    private String modelLabel;

    /** L1-Easy | L2-Mid | L3-Hard | null */
    @Column(name = "issue_level", columnDefinition = "TEXT")
    private String issueLevel;

    @Column(name = "case_type", columnDefinition = "TEXT")
    private String caseType;

    /** Online | On Site | ... */
    @Column(name = "service_mode", columnDefinition = "TEXT")
    private String serviceMode;

    @Column(name = "serial_number", columnDefinition = "TEXT")
    private String serialNumber;

    @Column(columnDefinition = "TEXT")
    private String customer;

    @Column(columnDefinition = "TEXT")
    private String branch;

    @Column(name = "main_issue", columnDefinition = "TEXT")
    private String mainIssue;

    @Column(name = "open_date")
    private LocalDate openDate;

    @Column(name = "action_date")
    private LocalDate actionDate;

    /** {@code [{"id":"123","name":"..."}]} from the RE People column; "[]" = unassigned. */
    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "people", nullable = false)
    @Builder.Default
    private String people = "[]";

    @Column(name = "monday_updated_at")
    private Instant mondayUpdatedAt;

    @Column(name = "is_open", nullable = false)
    @Builder.Default
    private boolean open = true;

    @Column(name = "first_seen_at", nullable = false)
    @Builder.Default
    private Instant firstSeenAt = Instant.now();

    @Column(name = "last_seen_at", nullable = false)
    @Builder.Default
    private Instant lastSeenAt = Instant.now();

    @Getter
    @Setter
    @NoArgsConstructor
    @AllArgsConstructor
    @EqualsAndHashCode
    public static class Key implements Serializable {
        private String boardId;
        private String itemId;
    }
}
