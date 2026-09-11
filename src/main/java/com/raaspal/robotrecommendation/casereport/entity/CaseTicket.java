package com.raaspal.robotrecommendation.casereport.entity;

import jakarta.persistence.*;
import lombok.*;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.UUID;

/**
 * The current state of one service ticket. Maps to {@code case_ticket} (V38).
 *
 * <p>One row per ticket, updated in place on every sync — this table answers "what is
 * true right now". What changed over time lives in {@link CaseTicketStatusHistory}, and
 * the comment threads in {@link CaseTicketUpdate}.
 *
 * <p><strong>The raw fields are raw on purpose.</strong> {@link #serialNumbers},
 * {@link #projectRaw} and {@link #branchRaw} hold exactly what the board holds, typos
 * included — {@code 'ท่าอาศยานสุวรรณภูมิ'} is missing a ก on a live ticket, and
 * {@code 'Marko ระนอง'} should read Makro. Normalisation happens at report time through
 * {@code case_branch_alias}, so correcting an alias fixes historical reports too rather
 * than only tickets synced after the fix.
 *
 * <p>Nothing here is ever deleted. A closed case has {@link #isPresent} set false: these
 * tickets appear in reports already sent to customers, and deleting one would erase the
 * record of what was reported.
 */
@Entity
@Table(name = "case_ticket")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class CaseTicket {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    @Builder.Default
    private CaseSource source = CaseSource.MONDAY;

    /** {@code 3451717331} cleaning, {@code 1647612496} delivery. */
    @Column(name = "source_board_id", nullable = false, columnDefinition = "TEXT")
    private String sourceBoardId;

    @Column(name = "source_item_id", nullable = false, columnDefinition = "TEXT")
    private String sourceItemId;

    @Column(name = "source_group_id", columnDefinition = "TEXT")
    private String sourceGroupId;

    /** {@code 'All Case'} — the only group either report reads. */
    @Column(name = "source_group_title", columnDefinition = "TEXT")
    private String sourceGroupTitle;

    @Column(name = "item_name", columnDefinition = "TEXT")
    private String itemName;

    /**
     * Raw, exactly as the board holds it.
     *
     * <p>One ticket can name several robots — separated by {@code /} on the cleaning
     * board and by {@code และ} on delivery — and the report splits those into one row
     * each. Splitting on the way <em>in</em> would lose the ability to trace a report
     * row back to the ticket it came from.
     */
    @Column(name = "serial_numbers", columnDefinition = "TEXT")
    private String serialNumbers;

    @Column(name = "project_raw", columnDefinition = "TEXT")
    private String projectRaw;

    @Column(name = "branch_raw", columnDefinition = "TEXT")
    private String branchRaw;

    /** The delivery board's Branch code tag: {@code M524}, {@code Y034}. */
    @Column(name = "branch_code_raw", columnDefinition = "TEXT")
    private String branchCodeRaw;

    /**
     * The จังหวัด dropdown from the ticket itself, when the board carries one.
     *
     * <p>The best province source there is, and the first tier the resolver tries:
     * stated at ticket creation by someone who knows where the robot is, rather than
     * inferred from a branch name or joined through a robot list that can be stale.
     * A blank shows as "-" in the next morning's report, which is a fast enough
     * feedback loop to keep it filled.
     *
     * <p>Only the delivery report's SLA actually depends on it — cleaning is 3 days
     * everywhere — so a blank here is not equally serious on both boards.
     */
    @Column(name = "province_raw", columnDefinition = "TEXT")
    private String provinceRaw;

    @Column(name = "robot_model", columnDefinition = "TEXT")
    private String robotModel;

    @Column(columnDefinition = "TEXT")
    private String status;

    @Column(name = "sup_status", columnDefinition = "TEXT")
    private String supStatus;

    @Column(name = "main_issue", columnDefinition = "TEXT")
    private String mainIssue;

    /**
     * The board's own Solution cell, as typed. On the delivery board it is empty on about
     * seven tickets in eight, and the report's Solution line is written from the comment
     * thread instead (see {@code MkPendingReportGenerator}). On the cleaning board it is
     * free text.
     */
    @Column(columnDefinition = "TEXT")
    private String solution;

    @Column(name = "open_date")
    private LocalDate openDate;

    @Column(name = "re_action_date")
    private LocalDate reActionDate;

    /**
     * The whole {@code column_values} payload, unparsed.
     *
     * <p>Costs almost nothing and means the first time someone needs a column we did
     * not map, it is already here — rather than needing a re-sync of the board, which
     * cannot recover history that has since changed.
     */
    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "raw_columns")
    private String rawColumns;

    @Column(name = "source_updated_at")
    private LocalDateTime sourceUpdatedAt;

    @CreationTimestamp
    @Column(name = "first_seen_at", nullable = false, updatable = false)
    private LocalDateTime firstSeenAt;

    /**
     * Set by the sync, not by {@code @UpdateTimestamp}.
     *
     * <p>Deliberate: a sync that finds nothing changed leaves the entity clean, so
     * Hibernate would issue no UPDATE and the timestamp would silently stop advancing —
     * making a stalled sync look like a quiet board.
     */
    @Column(name = "last_synced_at", nullable = false)
    private LocalDateTime lastSyncedAt;

    /**
     * False once the ticket leaves the watched group, i.e. the case closed.
     *
     * <p>Absence is inferred rather than reported: monday does not tell us a ticket
     * left, so the sync marks everything it did not see this run.
     */
    @Column(name = "is_present", nullable = false)
    @Builder.Default
    private boolean isPresent = true;
}
