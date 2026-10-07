package com.raaspal.robotrecommendation.casereport.aotsheet;

import org.apache.poi.ss.usermodel.Cell;
import org.apache.poi.ss.usermodel.CellStyle;
import org.apache.poi.ss.usermodel.FillPatternType;
import org.apache.poi.ss.usermodel.Font;
import org.apache.poi.ss.usermodel.IndexedColors;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.ss.usermodel.VerticalAlignment;
import org.apache.poi.ss.util.CellRangeAddress;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.springframework.stereotype.Component;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.function.Function;

/**
 * The AOTGA tracker as a workbook - the file the team can attach to an email - laid out as
 * the AOT tab is: the period chosen there, its cases in the order on the screen, and the
 * cases the tab lists apart (no issue date, no ticket number) on sheets of their own.
 */
@Component
public class AotgaExcelWriter {

    private static final String DATE_FORMAT = "dd/mm/yyyy";
    private static final DateTimeFormatter DAY = DateTimeFormatter.ofPattern("EEE d MMM yyyy", Locale.ENGLISH);

    /** The stages in the words the console uses. */
    static final Map<AotgaTracker.Stage, String> STAGE = Map.of(
            AotgaTracker.Stage.REPORTED, "Reported",
            AotgaTracker.Stage.PART_REQUESTED, "Spare part requested",
            AotgaTracker.Stage.OLD_PART_BACK, "Old part back",
            AotgaTracker.Stage.CLAIMED, "Sent to manufacturer",
            AotgaTracker.Stage.PENDING_REVIEW, "Pending review by RAAS",
            AotgaTracker.Stage.CASE_CLOSED, "Case closed");

    public enum Cadence { DAILY, WEEKLY, MONTHLY, ALL }

    /**
     * The period picked on the tab: the cases AOT issued in it - or every case, for all
     * time. Their stage is as of the day the list is read on: the period's last day when a
     * copy of it was kept, otherwise today.
     *
     * @param from first day covered; null for all time
     * @param to   last day covered; null for all time
     */
    public record Period(Cadence cadence, LocalDate from, LocalDate to) {

        public static final Period ALL_TIME = new Period(Cadence.ALL, null, null);

        /** One day's cases. */
        public static Period day(LocalDate day) {
            return new Period(Cadence.DAILY, day, day);
        }

        /** Whether a case belongs to the period: issued in it. A case with no issue date belongs to all time only. */
        boolean contains(AotgaTracker.Case c) {
            if (cadence == Cadence.ALL) return true;
            return c.issueDate() != null && !c.issueDate().isBefore(from) && !c.issueDate().isAfter(to);
        }

        /** "Weekly · cases issued Mon 5 Oct 2026 – Fri 9 Oct 2026". */
        String describe() {
            return switch (cadence) {
                case ALL -> "All time · every case";
                case DAILY -> "Daily · cases issued on " + DAY.format(from);
                case WEEKLY, MONTHLY -> (cadence == Cadence.WEEKLY ? "Weekly" : "Monthly")
                        + " · cases issued " + DAY.format(from) + " – " + DAY.format(to);
            };
        }

        /** "AOTGA_All_2026-10-06", "AOTGA_2026-10-06", "AOTGA_Weekly_2026-10-05_2026-10-09", "AOTGA_Monthly_2026-10". */
        public String fileName(LocalDate asOf) {
            return switch (cadence) {
                case ALL -> "AOTGA_All_" + asOf;
                case DAILY -> "AOTGA_" + from;
                case WEEKLY -> "AOTGA_Weekly_" + from + "_" + to;
                case MONTHLY -> "AOTGA_Monthly_" + from.toString().substring(0, 7);
            };
        }
    }

    private record Column(String header, int width, Function<AotgaTracker.Case, Object> value) {
    }

    private static final List<Column> CASE_COLUMNS = List.of(
            new Column("Location", 10, AotgaTracker.Case::site),
            new Column("Robot", 10, AotgaTracker.Case::robot),
            new Column("S/N", 20, AotgaTracker.Case::serial),
            new Column("Problem", 36, AotgaTracker.Case::problem),
            new Column("Issue Date", 12, AotgaTracker.Case::issueDate),
            new Column("Days", 7, c -> c.days() == null ? null : c.days().doubleValue()),
            new Column("Stage", 22, c -> STAGE.get(c.stage())),
            new Column("Sent to Manufacturer", 14, AotgaExcelWriter::sent),
            new Column("Sent On", 12, AotgaTracker.Case::claimedOn),
            new Column("Colour", 20, AotgaTracker.Case::colourLabel),
            new Column("Spare Part Requested", 28, AotgaTracker.Case::requestedPart),
            new Column("Spare Part Received", 14, AotgaTracker.Case::partSentOn),
            new Column("Old Part Back", 14, AotgaTracker.Case::oldPartBackOn));

    /** A ticketed case: its number first. */
    private static final List<Column> TRACKED = prepend(
            new Column("AOT Ticket no.", 16, AotgaTracker.Case::ticketNo), CASE_COLUMNS);

    private static final Column SHEET_ROW =
            new Column("Sheet row", 10, c -> c.sheetRow() == null ? null : c.sheetRow().doubleValue());

    /** A row with no number: its row on the sheet instead, so it can be found and fixed. */
    private static final List<Column> BY_ROW = prepend(SHEET_ROW, CASE_COLUMNS);

    /** A row whose number is on another row too: both, so the pair can be found and fixed. */
    private static final List<Column> REPEATED = prepend(SHEET_ROW, TRACKED);

    /** The day's view, as the tab shows it for this period and in this order. */
    public byte[] write(AotgaTracker.View view, Period period, AotgaSort sort) {
        Comparator<AotgaTracker.Case> order = sort.comparator();
        List<AotgaTracker.Case> cases = view.cases().stream().filter(period::contains).toList();
        List<AotgaTracker.Case> dated = cases.stream().filter(c -> c.issueDate() != null).sorted(order).toList();
        List<AotgaTracker.Case> undated = cases.stream().filter(c -> c.issueDate() == null).sorted(order).toList();
        List<AotgaTracker.Case> noTicket = view.noTicket() == null ? List.of() : view.noTicket().stream()
                .filter(period::contains)
                .sorted(Comparator.comparing(AotgaTracker.Case::sheetRow, Comparator.nullsLast(Comparator.<Integer>naturalOrder())))
                .toList();
        List<AotgaTracker.Case> repeated = view.repeated() == null ? List.of()
                : view.repeated().stream().filter(period::contains).toList();
        long undatedElsewhere = period.cadence() == Cadence.ALL ? 0
                : view.cases().stream().filter(c -> c.issueDate() == null).count();

        try (XSSFWorkbook workbook = new XSSFWorkbook()) {
            Styles styles = new Styles(workbook);
            String heading = "AOTGA cases as of " + DAY.format(view.asOf())
                    + (view.noCopyFor() == null ? ""
                    : " (no copy was kept for " + DAY.format(view.noCopyFor()) + ", so as they stand today)");
            String periodLine = period.describe() + " · sorted by " + sort.describe();

            String counts = cases.size() + (cases.size() == 1 ? " case" : " cases")
                    + (undatedElsewhere == 0 ? ""
                    : undatedElsewhere == 1 ? "; the 1 case with no issue date is only in the All time export"
                    : "; the " + undatedElsewhere + " cases with no issue date are only in the All time export")
                    + (undated.isEmpty() ? "" : "; the " + undated.size() + " with no issue date are on the sheet \"No issue date\"")
                    + (repeated.isEmpty() ? "" : "; " + repeated.size()
                            + " rows whose AOT ticket number is on another row too are on the sheet \"Same ticket number\"")
                    + (noTicket.isEmpty() ? "" : "; " + noTicket.size() + " rows with no AOT ticket number are on the sheet \"No ticket number\"");
            table(workbook.createSheet("AOTGA"), styles, List.of(heading, periodLine, counts), TRACKED, dated);
            if (!undated.isEmpty()) {
                table(workbook.createSheet("No issue date"), styles,
                        List.of(heading + " - no issue date on AOT's sheet", periodLine), TRACKED, undated);
            }
            if (!repeated.isEmpty()) {
                table(workbook.createSheet("Same ticket number"), styles,
                        List.of(heading + " - rows whose AOT ticket number is on another row too, not tracked",
                                period.describe()), REPEATED, repeated);
            }
            if (!noTicket.isEmpty()) {
                table(workbook.createSheet("No ticket number"), styles,
                        List.of(heading + " - rows with no AOT ticket number, by row on AOT's sheet",
                                period.describe()), BY_ROW, noTicket);
            }
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            workbook.write(out);
            return out.toByteArray();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    private static void table(Sheet sheet, Styles styles, List<String> lines, List<Column> columns,
                              List<AotgaTracker.Case> cases) {
        for (int i = 0; i < lines.size(); i++) {
            Cell cell = sheet.createRow(i).createCell(0);
            cell.setCellValue(lines.get(i));
            if (i == 0) cell.setCellStyle(styles.header);
        }
        int headRow = lines.size() + 1;
        Row head = sheet.createRow(headRow);
        for (int i = 0; i < columns.size(); i++) {
            Cell cell = head.createCell(i);
            cell.setCellValue(columns.get(i).header());
            cell.setCellStyle(styles.header);
            sheet.setColumnWidth(i, columns.get(i).width() * 256);
        }
        int r = headRow + 1;
        for (AotgaTracker.Case c : cases) {
            Row row = sheet.createRow(r++);
            for (int i = 0; i < columns.size(); i++) {
                Object value = columns.get(i).value().apply(c);
                Cell cell = row.createCell(i);
                if (value instanceof LocalDate d) {
                    cell.setCellValue(d);
                    cell.setCellStyle(styles.date);
                } else if (value instanceof Double n) {
                    cell.setCellValue(n);
                    cell.setCellStyle(styles.text);
                } else {
                    cell.setCellValue(value == null ? "" : value.toString());
                    cell.setCellStyle(styles.text);
                }
            }
        }
        sheet.createFreezePane(0, headRow + 1);
        if (!cases.isEmpty()) {
            sheet.setAutoFilter(new CellRangeAddress(headRow, headRow + cases.size(), 0, columns.size() - 1));
        }
    }

    /** The tab's column in words: blank until the old part is back, and for a case with no part. */
    private static String sent(AotgaTracker.Case c) {
        if (c.stage() == AotgaTracker.Stage.CLAIMED) return "Sent";
        return c.stage() == AotgaTracker.Stage.OLD_PART_BACK ? "Not yet" : null;
    }

    private static List<Column> prepend(Column first, List<Column> rest) {
        List<Column> all = new ArrayList<>(rest.size() + 1);
        all.add(first);
        all.addAll(rest);
        return List.copyOf(all);
    }

    private static final class Styles {
        final CellStyle text;
        final CellStyle date;
        final CellStyle header;

        Styles(XSSFWorkbook workbook) {
            text = workbook.createCellStyle();
            text.setVerticalAlignment(VerticalAlignment.TOP);
            text.setWrapText(true);
            date = workbook.createCellStyle();
            date.cloneStyleFrom(text);
            date.setDataFormat(workbook.createDataFormat().getFormat(DATE_FORMAT));
            header = workbook.createCellStyle();
            Font bold = workbook.createFont();
            bold.setBold(true);
            header.setFont(bold);
            header.setFillForegroundColor(IndexedColors.GREY_25_PERCENT.getIndex());
            header.setFillPattern(FillPatternType.SOLID_FOREGROUND);
        }
    }
}
