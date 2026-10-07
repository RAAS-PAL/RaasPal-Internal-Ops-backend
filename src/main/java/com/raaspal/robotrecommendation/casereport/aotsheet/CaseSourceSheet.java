package com.raaspal.robotrecommendation.casereport.aotsheet;

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

import java.time.OffsetDateTime;
import java.util.UUID;

/**
 * A Google Sheet case source as staff configured it in the console (V61). When a row
 * exists for a source it wins over the {@code app.googlesheet.aot.*} properties; the
 * credentials never live here.
 */
@Entity
@Table(name = "case_source_sheet")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class CaseSourceSheet {

    public static final String AOT = "AOT";

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @Column(name = "source_key", nullable = false, length = 32)
    private String sourceKey;

    @Column(name = "sheet_url", nullable = false, columnDefinition = "TEXT")
    private String sheetUrl;

    @Column(name = "spreadsheet_id", nullable = false, columnDefinition = "TEXT")
    private String spreadsheetId;

    @Column(nullable = false, columnDefinition = "TEXT")
    private String tab;

    @Column(name = "header_row", nullable = false)
    private int headerRow;

    @Column(name = "row_id_header", columnDefinition = "TEXT")
    private String rowIdHeader;

    @Column(name = "status_header", columnDefinition = "TEXT")
    private String statusHeader;

    @Column(name = "closed_statuses", columnDefinition = "TEXT")
    private String closedStatuses;

    @Column(name = "close_date_header", columnDefinition = "TEXT")
    private String closeDateHeader;

    /** The column whose cell colour is read; null means the status column (V65). */
    @Column(name = "colour_header", columnDefinition = "TEXT")
    private String colourHeader;

    /** Comma-separated {@code #rrggbb} colours that mean closed (V65). */
    @Column(name = "closed_colours", columnDefinition = "TEXT")
    private String closedColours;

    /** JSON: names given to other colours, as {@code [{"colour","label"}]} (V65). */
    @Column(name = "colour_labels", columnDefinition = "TEXT")
    private String colourLabels;

    /** The column the issue date is read from; null means "Issue Date" (V65). */
    @Column(name = "open_date_header", columnDefinition = "TEXT")
    private String openDateHeader;

    @Column(name = "sync_enabled", nullable = false)
    private boolean syncEnabled;

    @Column(name = "updated_by", columnDefinition = "TEXT")
    private String updatedBy;

    @Column(name = "updated_at", nullable = false)
    private OffsetDateTime updatedAt;
}
