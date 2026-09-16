package com.raaspal.robotrecommendation.kpi.repository;

import com.raaspal.robotrecommendation.kpi.csat.CsatStream;

import java.time.Instant;
import java.util.UUID;

/**
 * A workbook without its bytes — what the history list and the source status are
 * built from.
 *
 * <p>This exists so that listing history cannot accidentally select
 * {@code content}. A page of rows carrying whole workbooks would pull megabytes
 * through a connection pool capped at six, for a screen that only ever shows a
 * name, a size and a date.
 */
public interface KpiCsatWorkbookSummary {

    UUID getId();

    CsatStream getStream();

    String getFileName();

    long getSizeBytes();

    String getSha256();

    Instant getUploadedAt();

    UUID getUploadedBy();

    String getNote();
}
