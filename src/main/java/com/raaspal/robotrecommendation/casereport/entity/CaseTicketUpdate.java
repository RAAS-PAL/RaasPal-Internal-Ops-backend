package com.raaspal.robotrecommendation.casereport.entity;

import jakarta.persistence.*;
import lombok.*;
import org.hibernate.annotations.CreationTimestamp;

import java.time.LocalDateTime;
import java.util.UUID;

/**
 * One comment on a ticket. Maps to {@code case_ticket_update} (V38).
 *
 * <p><strong>The only place several report columns exist at all.</strong> Part Received,
 * Required Part and Waiting have no board column holding them —
 * {@code date_mm3b365t}, {@code dropdown_mknqq9fm} and {@code text_mksf2s9c} were
 * verified empty on all 46 live cleaning tickets. Whatever the AI produces for those
 * columns, it produces from here.
 *
 * <p>Stored newest-first as monday returns them: {@code updates[0]} is the most recent
 * comment, verified across tickets.
 */
@Entity
@Table(name = "case_ticket_update")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class CaseTicketUpdate {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    /**
     * Plain id rather than a {@code @ManyToOne}.
     *
     * <p>The sync writes thousands of these per run and never needs to navigate from a
     * comment back to its ticket; an association would buy a lazy proxy nobody reads
     * and an N+1 risk in every generator that touches comments.
     */
    @Column(name = "case_ticket_id", nullable = false)
    private UUID caseTicketId;

    @Column(name = "source_update_id", nullable = false, columnDefinition = "TEXT")
    private String sourceUpdateId;

    /** Null for a top-level comment; otherwise the comment this one replies to. */
    @Column(name = "parent_update_id", columnDefinition = "TEXT")
    private String parentUpdateId;

    /** {@code text_body}, never the HTML body. */
    @Column(columnDefinition = "TEXT")
    private String body;

    /**
     * Worth keeping rather than discarding as metadata.
     *
     * <p>The {@code *status*} convention that marks a case's state is one person's
     * habit, not a team standard — every observed instance was written by "Boss", and
     * it appears in the latest comment on only 3 of 12 tickets. A prompt can weight
     * comments by author because this column is here, and the low hit rate is why a
     * staff review step is mandatory rather than optional.
     */
    @Column(name = "creator_name", columnDefinition = "TEXT")
    private String creatorName;

    @Column(name = "posted_at")
    private LocalDateTime postedAt;

    @CreationTimestamp
    @Column(name = "synced_at", nullable = false, updatable = false)
    private LocalDateTime syncedAt;
}
