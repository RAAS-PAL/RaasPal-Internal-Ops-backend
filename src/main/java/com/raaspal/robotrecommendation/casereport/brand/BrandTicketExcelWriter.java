package com.raaspal.robotrecommendation.casereport.brand;

import com.raaspal.robotrecommendation.casereport.brand.dto.BrandTicket;
import com.raaspal.robotrecommendation.casereport.brand.dto.BrandTicketSummary;
import com.raaspal.robotrecommendation.casereport.brand.dto.BrandTicketSummary.Count;
import org.apache.poi.ss.usermodel.*;
import org.apache.poi.ss.util.CellRangeAddress;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.springframework.stereotype.Component;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.function.Function;

/**
 * The brand's tickets as a three-sheet workbook: Summary, Tickets, Comments.
 *
 * <p>The same layout the team already received by hand for the AutoXing review, so the
 * export button replaces a script rather than introducing a new file to learn. Dates are
 * real dates and counts real numbers; the thread is repeated inline on the Tickets sheet
 * (one cell, chronological) and again one-row-per-comment on Comments, because the first
 * reads well and the second filters well.
 */
@Component
public class BrandTicketExcelWriter {

    private static final DateTimeFormatter DAY = DateTimeFormatter.ISO_LOCAL_DATE;
    private static final DateTimeFormatter STAMP = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm");
    private static final int CHAR = 256;
    /** Excel refuses a cell longer than this. */
    private static final int CELL_LIMIT = 32_000;

    private record Column(String header, int width, Function<BrandTicket, Object> value) {
    }

    private static final List<Column> TICKET_COLUMNS = List.of(
            new Column("Case ID", 12, BrandTicket::itemId),
            new Column("Name", 48, BrandTicket::name),
            new Column("Group", 18, BrandTicket::group),
            new Column("Open?", 7, t -> t.open() ? "Open" : "Done"),
            new Column("Status", 20, BrandTicket::status),
            new Column("Sup Status", 22, BrandTicket::supStatus),
            new Column("Open Date", 12, BrandTicket::openDate),
            new Column("RE Action", 12, BrandTicket::reActionDate),
            new Column("Days to action", 9, BrandTicket::daysToAction),
            new Column("Age (days)", 9, BrandTicket::ageDays),
            new Column("Project", 22, BrandTicket::project),
            new Column("Branch", 18, BrandTicket::branch),
            new Column("Branch code", 12, BrandTicket::branchCode),
            new Column("Province", 14, BrandTicket::province),
            new Column("Model", 12, BrandTicket::model),
            new Column("Serial", 20, BrandTicket::serial),
            new Column("Type of case", 14, BrandTicket::caseType),
            new Column("Level", 10, BrandTicket::level),
            new Column("Root cause", 18, BrandTicket::rootCause),
            new Column("RE", 10, BrandTicket::reOwner),
            new Column("Channel", 14, BrandTicket::channel),
            new Column("Under warranty", 12, BrandTicket::underWarranty),
            new Column("Main issue", 40, BrandTicket::mainIssue),
            new Column("Solution", 40, BrandTicket::solution),
            new Column("Comments (count)", 9, t -> t.comments().size()),
            new Column("Comments (chronological)", 80, BrandTicketExcelWriter::threadText),
            new Column("monday link", 30, BrandTicket::mondayUrl));

    public byte[] write(BrandTicketSummary summary, List<BrandTicket> tickets) {
        try (XSSFWorkbook wb = new XSSFWorkbook()) {
            Styles styles = new Styles(wb);
            writeSummary(wb.createSheet("Summary"), styles, summary);
            writeTickets(wb.createSheet("Tickets"), styles, tickets);
            writeComments(wb.createSheet("Comments"), styles, tickets);

            ByteArrayOutputStream out = new ByteArrayOutputStream();
            wb.write(out);
            return out.toByteArray();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    public String filename(BrandTicketSummary summary) {
        String range = summary.from() == null && summary.to() == null
                ? "all"
                : (summary.from() == null ? "" : DAY.format(summary.from()))
                        + "_to_" + (summary.to() == null ? "" : DAY.format(summary.to()));
        return summary.label().replaceAll("[^A-Za-z0-9]", "") + "_Tickets_" + range + ".xlsx";
    }

    private static void writeSummary(Sheet sheet, Styles s, BrandTicketSummary sum) {
        int r = 0;
        Row title = sheet.createRow(r++);
        title.createCell(0).setCellValue(sum.label() + " service tickets — monday board " + sum.boardId());
        title.getCell(0).setCellStyle(s.title);
        sheet.addMergedRegion(new CellRangeAddress(0, 0, 0, 3));

        String range = (sum.from() == null ? "start" : DAY.format(sum.from()))
                + " to " + (sum.to() == null ? "today" : DAY.format(sum.to()));
        sheet.createRow(r++).createCell(0).setCellValue("Range: " + range
                + " · exported " + STAMP.format(LocalDateTime.now())
                + (sum.lastSyncedAt() == null ? "" : " · board synced " + STAMP.format(sum.lastSyncedAt())));
        r++;

        r = block(sheet, s, r, "Key figures", "Metric", List.of(
                new Count("Tickets in range", sum.totals().tickets()),
                new Count("Open (in range)", sum.totals().open()),
                new Count("Done (in range)", sum.totals().done()),
                new Count("Robots affected", sum.totals().robots()),
                new Count("Sites affected", sum.totals().sites()),
                new Count("Open now (all time)", sum.kpis().openNow()),
                new Count("This month", sum.kpis().thisMonth()),
                new Count("Last month", sum.kpis().lastMonth())));

        Row kpi = sheet.createRow(r++);
        kpi.createCell(0).setCellValue("Median days to RE action");
        setNumber(kpi.createCell(1), sum.kpis().medianDaysToAction(), s);
        kpi = sheet.createRow(r++);
        kpi.createCell(0).setCellValue("Within 7-day SLA (%)");
        setNumber(kpi.createCell(1), sum.kpis().slaWithin7Pct(), s);
        kpi = sheet.createRow(r++);
        kpi.createCell(0).setCellValue("Repeat within 14 days (%)");
        setNumber(kpi.createCell(1), sum.kpis().repeatRatePct(), s);
        r++;

        r = block(sheet, s, r, "By month", "Month", sum.monthly().stream()
                .map(m -> new Count(m.month(), m.opened())).toList());
        r = block(sheet, s, r, "By status", "Status", sum.statuses());
        r = block(sheet, s, r, "By root cause", "Root cause", sum.rootCauses());
        r = block(sheet, s, r, "By model", "Model", sum.models());
        r = block(sheet, s, r, "By RE", "RE", sum.reOwners());
        r = block(sheet, s, r, "Open tickets by age", "Days", sum.aging());
        block(sheet, s, r, "Top sites", "Project", sum.topSites().stream()
                .map(site -> new Count(site.label(), site.count())).toList());

        sheet.setColumnWidth(0, 44 * CHAR);
        sheet.setColumnWidth(1, 12 * CHAR);
    }

    private static int block(Sheet sheet, Styles s, int r, String heading, String label, List<Count> rows) {
        Row h = sheet.createRow(r++);
        h.createCell(0).setCellValue(heading);
        h.getCell(0).setCellStyle(s.heading);

        Row head = sheet.createRow(r++);
        head.createCell(0).setCellValue(label);
        head.createCell(1).setCellValue("Tickets");
        head.getCell(0).setCellStyle(s.header);
        head.getCell(1).setCellStyle(s.header);

        int total = 0;
        for (Count c : rows) {
            Row row = sheet.createRow(r++);
            row.createCell(0).setCellValue(c.label());
            row.createCell(1).setCellValue(c.count());
            row.getCell(0).setCellStyle(s.text);
            row.getCell(1).setCellStyle(s.number);
            total += c.count();
        }
        Row t = sheet.createRow(r++);
        t.createCell(0).setCellValue("Total");
        t.createCell(1).setCellValue(total);
        t.getCell(0).setCellStyle(s.bold);
        t.getCell(1).setCellStyle(s.number);
        return r + 1;
    }

    private static void writeTickets(Sheet sheet, Styles s, List<BrandTicket> tickets) {
        Row header = sheet.createRow(0);
        for (int c = 0; c < TICKET_COLUMNS.size(); c++) {
            Cell cell = header.createCell(c);
            cell.setCellValue(TICKET_COLUMNS.get(c).header());
            cell.setCellStyle(s.header);
            sheet.setColumnWidth(c, TICKET_COLUMNS.get(c).width() * CHAR);
        }
        int r = 1;
        for (BrandTicket t : tickets) {
            Row row = sheet.createRow(r++);
            for (int c = 0; c < TICKET_COLUMNS.size(); c++) {
                setValue(row.createCell(c), TICKET_COLUMNS.get(c).value().apply(t), s);
            }
        }
        sheet.createFreezePane(2, 1);
        if (r > 1) sheet.setAutoFilter(new CellRangeAddress(0, r - 1, 0, TICKET_COLUMNS.size() - 1));
    }

    private static void writeComments(Sheet sheet, Styles s, List<BrandTicket> tickets) {
        String[] headers = {"Case ID", "Ticket name", "Open Date", "Kind", "Comment ID", "Posted at", "Author", "Text"};
        int[] widths = {12, 48, 12, 10, 14, 17, 18, 100};
        Row header = sheet.createRow(0);
        for (int c = 0; c < headers.length; c++) {
            Cell cell = header.createCell(c);
            cell.setCellValue(headers[c]);
            cell.setCellStyle(s.header);
            sheet.setColumnWidth(c, widths[c] * CHAR);
        }
        int r = 1;
        for (BrandTicket t : tickets) {
            for (BrandTicket.Comment c : t.comments()) {
                Row row = sheet.createRow(r++);
                setValue(row.createCell(0), t.itemId(), s);
                setValue(row.createCell(1), t.name(), s);
                setValue(row.createCell(2), t.openDate(), s);
                setValue(row.createCell(3), c.parentId() == null ? "Update" : "Reply", s);
                setValue(row.createCell(4), c.id(), s);
                setValue(row.createCell(5), c.postedAt(), s);
                setValue(row.createCell(6), c.author(), s);
                setValue(row.createCell(7), c.body(), s);
            }
        }
        sheet.createFreezePane(0, 1);
        if (r > 1) sheet.setAutoFilter(new CellRangeAddress(0, r - 1, 0, headers.length - 1));
    }

    private static String threadText(BrandTicket t) {
        StringBuilder sb = new StringBuilder();
        for (BrandTicket.Comment c : t.comments()) {
            if (sb.length() > 0) sb.append("\n\n");
            sb.append('[').append(c.postedAt() == null ? "—" : STAMP.format(c.postedAt())).append("] ")
                    .append(c.author() == null ? "unknown" : c.author())
                    .append(c.parentId() == null ? "" : " (reply)")
                    .append(": ").append(c.body() == null ? "" : c.body().trim());
        }
        return sb.length() > CELL_LIMIT ? sb.substring(0, CELL_LIMIT) : sb.toString();
    }

    private static void setValue(Cell cell, Object value, Styles s) {
        switch (value) {
            case null -> cell.setCellStyle(s.text);
            case LocalDate d -> {
                cell.setCellValue(d);
                cell.setCellStyle(s.date);
            }
            case LocalDateTime dt -> {
                cell.setCellValue(dt);
                cell.setCellStyle(s.dateTime);
            }
            case Number n -> {
                cell.setCellValue(n.doubleValue());
                cell.setCellStyle(s.number);
            }
            default -> {
                String text = value.toString();
                cell.setCellValue(text.length() > CELL_LIMIT ? text.substring(0, CELL_LIMIT) : text);
                cell.setCellStyle(text.length() > 40 || text.contains("\n") ? s.wrap : s.text);
            }
        }
    }

    private static void setNumber(Cell cell, Double value, Styles s) {
        if (value != null) cell.setCellValue(value);
        cell.setCellStyle(s.number);
    }

    private static final class Styles {
        final CellStyle title, heading, header, text, wrap, date, dateTime, number, bold;

        Styles(XSSFWorkbook wb) {
            Font titleFont = wb.createFont();
            titleFont.setBold(true);
            titleFont.setFontHeightInPoints((short) 14);
            title = wb.createCellStyle();
            title.setFont(titleFont);

            Font boldFont = wb.createFont();
            boldFont.setBold(true);
            heading = wb.createCellStyle();
            heading.setFont(boldFont);
            bold = wb.createCellStyle();
            bold.setFont(boldFont);

            Font headerFont = wb.createFont();
            headerFont.setBold(true);
            headerFont.setColor(IndexedColors.WHITE.getIndex());
            header = wb.createCellStyle();
            header.setFont(headerFont);
            header.setFillForegroundColor(IndexedColors.DARK_BLUE.getIndex());
            header.setFillPattern(FillPatternType.SOLID_FOREGROUND);
            header.setVerticalAlignment(VerticalAlignment.CENTER);
            header.setWrapText(true);

            text = wb.createCellStyle();
            text.setVerticalAlignment(VerticalAlignment.TOP);
            wrap = wb.createCellStyle();
            wrap.cloneStyleFrom(text);
            wrap.setWrapText(true);

            DataFormat fmt = wb.createDataFormat();
            date = wb.createCellStyle();
            date.cloneStyleFrom(text);
            date.setDataFormat(fmt.getFormat("yyyy-mm-dd"));
            dateTime = wb.createCellStyle();
            dateTime.cloneStyleFrom(text);
            dateTime.setDataFormat(fmt.getFormat("yyyy-mm-dd hh:mm"));
            number = wb.createCellStyle();
            number.cloneStyleFrom(text);
            number.setAlignment(HorizontalAlignment.RIGHT);
        }
    }
}
