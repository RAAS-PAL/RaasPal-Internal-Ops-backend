package com.raaspal.robotrecommendation.kpi.csat;

import com.raaspal.robotrecommendation.common.exception.BadRequestException;
import com.raaspal.robotrecommendation.kpi.config.KpiCsatProperties;
import lombok.RequiredArgsConstructor;

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
 * <p>A local escape hatch, used only when {@code app.kpi.csat.folder} is set.
 * Every deployment leaves it blank and reads the workbooks uploaded in the
 * console instead ({@link DatabaseCsatWorkbookSource});
 * {@link com.raaspal.robotrecommendation.kpi.config.CsatWorkbookSourceConfig}
 * picks between the two.
 *
 * <p>Only {@code .xlsx} files count, and never Excel's {@code ~$} lock files:
 * one of those appears whenever a workbook is open on someone's desk, and it
 * is not a workbook.
 */
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
            // Only reachable if this source was built without a folder, which the
            // config does not do — it picks the database source instead.
            throw new BadRequestException("app.kpi.csat.folder is not set. Leave it unset to read the "
                    + "workbooks uploaded in the console, or point it at a folder of .xlsx workbooks "
                    + "to read those instead.");
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
