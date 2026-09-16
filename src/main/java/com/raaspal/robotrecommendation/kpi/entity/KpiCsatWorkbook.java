package com.raaspal.robotrecommendation.kpi.entity;

import com.raaspal.robotrecommendation.kpi.csat.CsatStream;
import jakarta.persistence.*;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.Instant;
import java.util.UUID;

/**
 * One uploaded CSAT survey workbook, bytes and all. See
 * {@code V49__add_csat_workbook_uploads.sql} for why these live in the database
 * rather than on the uploads volume.
 *
 * <p>Append-only: a new upload for a survey is a new row, never an edit of the
 * one before it, and the current workbook for a survey is simply its most recent
 * row. Nothing here is mutated after insert.
 */
@Entity
@Table(name = "kpi_csat_workbook")
@Getter
@Setter
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class KpiCsatWorkbook {

    /**
     * DDL hint only, and only for the tests. The real column is the unbounded
     * BYTEA that V49 declares; Flyway builds production, never this annotation.
     * The tests run Hibernate's ddl-auto against H2, whose varbinary tops out
     * here — one byte more and Hibernate emits "blob", which H2 does not
     * recognise in PostgreSQL mode, and the whole test schema fails to build.
     *
     * <p>So this is NOT the upload limit: {@code CsatWorkbookUploadService}
     * enforces that, and it is larger. A test fixture above 1 MB would fail on
     * H2 alone; survey workbooks are a few hundred KB, so none comes close.
     */
    private static final int H2_VARBINARY_MAX = 1_000_000;

    @Id
    @GeneratedValue
    private UUID id;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 16)
    private CsatStream stream;

    @Column(name = "file_name", nullable = false)
    private String fileName;

    @Column(name = "size_bytes", nullable = false)
    private long sizeBytes;

    @Column(nullable = false, length = 64)
    private String sha256;

    /**
     * Deliberately not {@code @Lob}. With it, Hibernate stores a byte[] on
     * Postgres as a large-object OID rather than in the column, which is not
     * what V49 declares and leaves the bytes outside the row (and outside a
     * plain dump). Unannotated, it maps to bytea, which is the intent.
     *
     * <p>VARBINARY rather than the default: that is bytea on Postgres, which is
     * what V49 declares, and varbinary under H2. See {@link #H2_VARBINARY_MAX}
     * for why the length is what it is — it constrains the test schema only.
     *
     * <p>LAZY so loading the entity for its metadata does not drag the workbook
     * with it. The history list avoids this column entirely by projecting to
     * {@code KpiCsatWorkbookSummary}; only a parse or a download reads it.
     */
    @Basic(fetch = FetchType.LAZY)
    @JdbcTypeCode(SqlTypes.VARBINARY)
    @Column(nullable = false, length = H2_VARBINARY_MAX)
    private byte[] content;

    @Column(name = "uploaded_at", nullable = false)
    private Instant uploadedAt;

    /** Null once the uploader's account is deleted; the row itself stays. */
    @Column(name = "uploaded_by")
    private UUID uploadedBy;

    private String note;
}
