package com.raaspal.robotrecommendation.kpi.repository;

import com.raaspal.robotrecommendation.kpi.csat.CsatStream;
import com.raaspal.robotrecommendation.kpi.entity.KpiCsatWorkbook;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Reads here come in two shapes and keeping them apart is the point: metadata
 * for the console, and bytes only at the moment something parses or downloads a
 * workbook.
 *
 * <p>That split is not tidiness. {@code CsatWorkbookSource.list()} is called on
 * every CSAT request just to compute the fingerprint signature that decides
 * whether anything needs re-parsing, and it almost always has not. Returning
 * entities there would load four workbooks out of the database to answer a
 * question about their names and sizes.
 */
@Repository
public interface KpiCsatWorkbookRepository extends JpaRepository<KpiCsatWorkbook, UUID> {

    /**
     * The current workbook for each survey — the most recent row per stream,
     * without its bytes.
     *
     * <p>Derived rather than flagged, so deleting a row promotes the one before
     * it with nothing to update. Written as a correlated max() rather than
     * Postgres's DISTINCT ON so the same query runs under H2 in the tests.
     */
    @Query("""
            select w.id as id, w.stream as stream, w.fileName as fileName,
                   w.sizeBytes as sizeBytes, w.sha256 as sha256,
                   w.uploadedAt as uploadedAt, w.uploadedBy as uploadedBy, w.note as note
            from KpiCsatWorkbook w
            where w.uploadedAt = (
                select max(o.uploadedAt) from KpiCsatWorkbook o where o.stream = w.stream
            )
            order by w.stream
            """)
    List<KpiCsatWorkbookSummary> findCurrent();

    /** Every upload, newest first — the history list. No bytes. */
    @Query("""
            select w.id as id, w.stream as stream, w.fileName as fileName,
                   w.sizeBytes as sizeBytes, w.sha256 as sha256,
                   w.uploadedAt as uploadedAt, w.uploadedBy as uploadedBy, w.note as note
            from KpiCsatWorkbook w
            order by w.uploadedAt desc, w.fileName asc
            """)
    List<KpiCsatWorkbookSummary> findHistory();

    /** One row's metadata, for a download's file name and a delete's response. */
    @Query("""
            select w.id as id, w.stream as stream, w.fileName as fileName,
                   w.sizeBytes as sizeBytes, w.sha256 as sha256,
                   w.uploadedAt as uploadedAt, w.uploadedBy as uploadedBy, w.note as note
            from KpiCsatWorkbook w where w.id = ?1
            """)
    Optional<KpiCsatWorkbookSummary> findSummary(UUID id);

    /** The bytes, and only when something is about to read them. */
    @Query("select w.content from KpiCsatWorkbook w where w.id = ?1")
    Optional<byte[]> findContent(UUID id);

    /**
     * The current file's hash for one survey, so re-uploading the identical file
     * can be answered without loading either copy.
     */
    @Query("""
            select w.sha256 from KpiCsatWorkbook w
            where w.stream = ?1
              and w.uploadedAt = (select max(o.uploadedAt) from KpiCsatWorkbook o where o.stream = ?1)
            """)
    Optional<String> findCurrentSha256(CsatStream stream);
}
