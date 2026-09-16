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
 * One generated report, and its audit trail. Maps to {@code case_report_run} (V38).
 *
 * <p><strong>This is what makes a report reproducible.</strong> The source board is read
 * live and people edit it all day, so regenerating an earlier date from monday returns
 * today's state, not that day's: a case that has since closed vanishes, and one whose
 * status moved reports the new value under the old date. Freezing {@link #rowsJson} on
 * first generation is what stops a report changing after the fact.
 *
 * <p>{@code uq_case_report_run_day} allows one run per report per date, which is also the
 * guard against double-sending: a LINE message cannot be recalled, and two app instances
 * hitting the same cron would otherwise both deliver.
 */
@Entity
@Table(name = "case_report_run")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class CaseReportRun {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @Column(name = "definition_id", nullable = false)
    private UUID definitionId;

    /** The business date the report covers, in the definition's zone. */
    @Column(name = "run_date", nullable = false)
    private LocalDate runDate;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 24)
    @Builder.Default
    private CaseRunStatus status = CaseRunStatus.GENERATING;

    @Column(name = "ticket_count", nullable = false)
    @Builder.Default
    private Integer ticketCount = 0;

    /**
     * The generated rows, as JSON, and the thing staff edit.
     *
     * <p>The Excel is rendered from here at send time and never earlier: a file built at
     * generation time would not contain the corrections made during review, and somebody
     * would approve a report and send a stale spreadsheet.
     */
    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "rows_json")
    private String rowsJson;

    /** Anything the model flagged as uncertain, for a reviewer to look at first. */
    @Column(name = "ai_notes", columnDefinition = "TEXT")
    private String aiNotes;

    /** Stays null until approval, for the reason given on {@link #rowsJson}. */
    @Column(name = "excel_path", columnDefinition = "TEXT")
    private String excelPath;

    /** Opens at {@code /report/{token}} — LINE cannot carry the file itself. */
    @Column(name = "report_token", length = 64)
    private String reportToken;

    @Column(name = "generated_at")
    private LocalDateTime generatedAt;

    @Column(name = "approved_by")
    private UUID approvedBy;

    @Column(name = "approved_at")
    private LocalDateTime approvedAt;

    @Column(name = "sent_at")
    private LocalDateTime sentAt;

    @Column(name = "error_message", columnDefinition = "TEXT")
    private String errorMessage;

    @CreationTimestamp
    @Column(name = "created_at", nullable = false, updatable = false)
    private LocalDateTime createdAt;
}
