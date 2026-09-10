package com.raaspal.robotrecommendation.kpi.csat;

import com.raaspal.robotrecommendation.common.exception.BadRequestException;
import com.raaspal.robotrecommendation.kpi.config.KpiCsatProperties;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.stream.Stream;

/**
 * The workbooks as files in one folder — {@code app.kpi.csat.folder}. The RE
 * team drops the month's four workbooks there, overwriting last month's, so the
 * folder is always the current set and nothing older.
 *
 * <p>Only {@code .xlsx} files count, and never Excel's {@code ~$} lock files:
 * one of those appears whenever a workbook is open on someone's desk, and it
 * is not a workbook.
 */
@Component
@RequiredArgsConstructor
public class FolderCsatWorkbookSource implements CsatWorkbookSource {

    private final KpiCsatProperties properties;

    @Override
    public String describe() {
        String folder = properties.getFolder();
        return folder == null || folder.isBlank() ? "(not configured)" : Path.of(folder).toAbsolutePath().toString();
    }

    @Override
    public List<WorkbookFile> list() {
        String folder = properties.getFolder();
        if (folder == null || folder.isBlank()) {
            throw new BadRequestException("CSAT survey workbooks are not configured: set KPI_CSAT_FOLDER "
                    + "(app.kpi.csat.folder) to the folder holding the four survey workbooks");
        }
        Path dir = Path.of(folder);
        if (!Files.isDirectory(dir)) {
            throw new BadRequestException("CSAT workbook folder does not exist: " + dir.toAbsolutePath());
        }
        List<WorkbookFile> files;
        try (Stream<Path> entries = Files.list(dir)) {
            files = entries
                    .filter(Files::isRegularFile)
                    .filter(FolderCsatWorkbookSource::isWorkbook)
                    .sorted(Comparator.comparing(p -> p.getFileName().toString()))
                    .map(FolderCsatWorkbookSource::describeFile)
                    .toList();
        } catch (IOException e) {
            throw new UncheckedIOException("Could not list CSAT workbook folder " + dir.toAbsolutePath(), e);
        }
        if (files.isEmpty()) {
            throw new BadRequestException("No .xlsx workbooks in the CSAT folder " + dir.toAbsolutePath());
        }
        return files;
    }

    private static boolean isWorkbook(Path path) {
        String name = path.getFileName().toString();
        return name.toLowerCase(Locale.ROOT).endsWith(".xlsx") && !name.startsWith("~$");
    }

    private static WorkbookFile describeFile(Path path) {
        try {
            return new WorkbookFile(
                    path.getFileName().toString(),
                    Files.size(path),
                    Files.getLastModifiedTime(path).toInstant(),
                    () -> Files.newInputStream(path));
        } catch (IOException e) {
            throw new UncheckedIOException("Could not stat " + path, e);
        }
    }
}
