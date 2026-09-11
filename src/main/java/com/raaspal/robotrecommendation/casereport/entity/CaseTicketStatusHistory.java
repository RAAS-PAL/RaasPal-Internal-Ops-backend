package com.raaspal.robotrecommendation.casereport.entity;

import jakarta.persistence.*;
import lombok.*;
import org.hibernate.annotations.CreationTimestamp;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.UUID;

/**
 * One observed status change. Maps to {@code case_ticket_status_history} (V38).
 *
 * <p>This is what the delivery report calls "Solution". That column is not prose — it
 * is a status log, one line per change, where {@code "24-Aug อยู่ระหว่างจัดส่งอะไหล่"} is
 * simply the date plus the value of the board's status column on that date. Confirmed
 * against item {@code 12874545928}.
 *
 * <p><strong>So the daily sync builds that column for free.</strong> No AI, no prompt,
 * no review — reading it back is an ordered select.
 *
 * <p>A row is written <em>only</em> when the status differs from the previous one, which
 * keeps the table small and makes the log read as a list of changes rather than a list
 * of days. The unique constraint on {@code (case_ticket_id, observed_on)} caps it at one
 * row per ticket per day: a status flipped twice in an afternoon must not put two lines
 * under the same date in a customer's report.
 */
@Entity
@Table(name = "case_ticket_status_history")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class CaseTicketStatusHistory {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    /** Plain id, for the same reason as {@link CaseTicketUpdate#getCaseTicketId()}. */
    @Column(name = "case_ticket_id", nullable = false)
    private UUID caseTicketId;

    @Column(columnDefinition = "TEXT")
    private String status;

    /**
     * Kept alongside {@link #status} because the two columns collide across boards:
     * {@code status_1} is Issue Level on the cleaning board but Sup Status on delivery.
     * Both are recorded so a generator reads the one its board means.
     */
    @Column(name = "sup_status", columnDefinition = "TEXT")
    private String supStatus;

    /**
     * The business date the change was observed, in {@code Asia/Bangkok}.
     *
     * <p>Not derived from {@link #observedAt}: a sync running just after midnight UTC
     * is still the previous business day in Bangkok, and the report is read by people
     * in Bangkok.
     */
    @Column(name = "observed_on", nullable = false)
    private LocalDate observedOn;

    @CreationTimestamp
    @Column(name = "observed_at", nullable = false, updatable = false)
    private LocalDateTime observedAt;
}
