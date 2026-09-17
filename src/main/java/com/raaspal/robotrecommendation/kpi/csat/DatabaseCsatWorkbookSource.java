package com.raaspal.robotrecommendation.kpi.csat;

import com.raaspal.robotrecommendation.common.exception.BadRequestException;
import com.raaspal.robotrecommendation.kpi.repository.KpiCsatWorkbookRepository;
import com.raaspal.robotrecommendation.kpi.repository.KpiCsatWorkbookSummary;
import lombok.RequiredArgsConstructor;

import java.io.ByteArrayInputStream;
import java.util.List;

/**
 * The workbooks as rows in {@code kpi_csat_workbook}, uploaded through the
 * console. This is what the deployed console reads.
 *
 * <p>It replaced an S3 bucket, which cost nine settings and a key pair to hold
 * four small files that change once a month. Uploading through the console
 * means the team needs no credentials, the file is stored beside the figures
 * computed from it, and every replacement is recorded rather than overwriting
 * the last one silently.
 *
 * <p>Only the current set is returned — the most recent upload per survey.
 * Older rows stay in the table as history and are never parsed.
 */
@RequiredArgsConstructor
public class DatabaseCsatWorkbookSource implements CsatWorkbookSource {

    private final KpiCsatWorkbookRepository workbooks;

    @Override
    public String describe() {
        return "uploaded workbooks (kpi_csat_workbook)";
    }

    @Override
    public List<WorkbookFile> list() {
        List<KpiCsatWorkbookSummary> current = workbooks.findCurrent();
        if (current.isEmpty()) {
            throw new BadRequestException("No CSAT survey workbooks have been uploaded yet. "
                    + "Upload the month's workbooks on the CSAT page — one per survey: "
                    + "installation, PM, CM cleaning and CM delivery.");
        }
        return current.stream().map(this::toFile).toList();
    }

    /**
     * The bytes are fetched inside the opener, not here: {@code list()} runs on
     * every CSAT request to work out whether anything changed, and only a parse
     * actually needs a workbook.
     *
     * <p>{@code uploadedAt} stands in for the file's mtime throughout. It is the
     * better clock: copying a file or downloading it from Drive resets mtime,
     * whereas an upload is someone saying "this is the one now". It is also what
     * {@code fingerprint()} folds into the cache signature, so re-uploading marks
     * the set changed even when the file keeps its name and size.
     */
    private WorkbookFile toFile(KpiCsatWorkbookSummary row) {
        return new WorkbookFile(
                row.getFileName(),
                row.getSizeBytes(),
                row.getUploadedAt(),
                () -> new ByteArrayInputStream(workbooks.findContent(row.getId())
                        .orElseThrow(() -> new BadRequestException(
                                "The workbook '" + row.getFileName() + "' was deleted while it was being read"))));
    }
}
