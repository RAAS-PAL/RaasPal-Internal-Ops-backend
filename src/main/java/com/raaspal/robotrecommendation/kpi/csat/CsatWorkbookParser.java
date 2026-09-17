package com.raaspal.robotrecommendation.kpi.csat;

import com.raaspal.robotrecommendation.kpi.csat.CsatWorkbook.MonthAggregate;
import lombok.extern.slf4j.Slf4j;
import org.apache.poi.ss.usermodel.Cell;
import org.apache.poi.ss.usermodel.CellType;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.ss.usermodel.Workbook;
import org.apache.poi.ss.usermodel.WorkbookFactory;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.io.InputStream;
import java.time.Instant;
import java.time.YearMonth;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Reads one survey workbook: three cells from each month sheet.
 *
 * <p>The workbooks are the RE team's own monthly report, and Top Box is
 * already worked out on every sheet: the cell beside the "Top Box" label is
 * {@code =AVERAGE(Q10:Q14)}, the average of the five questions' share of
 * 5-ratings. This reads that result as it is, with the customers-called and
 * responses tallies at the top. The job is to read and show, not to correct;
 * the RE team owns the arithmetic on their sheets, and a dashboard that
 * disagreed with the file they can open would be the one that looked wrong.
 *
 * <p>Two column sums are also read from the question table — ratings of 5
 * and ratings given — for one purpose only: combining months or surveys into
 * a total, which no sheet holds. The deck does that as all fives over all
 * ratings, and so does this. They never change a month's own Top Box.
 *
 * <p>Each month is a sheet named like {@code Jan 2026} or {@code August 2026}
 * — the team is not consistent about abbreviating. Every figure is found by
 * its label, never by its cell address: the block sits at different rows in
 * different months, and the team will keep editing these sheets.
 *
 * <p>Every other sheet — the per-case detail, the summaries, the suggestions —
 * is ignored without being read. Those name customers and this has no need of
 * them.
 */
@Slf4j
@Component
public class CsatWorkbookParser {

    /** "Jan 2026", "June 2026", "Sept. 2026" — a month word, then the year. */
    private static final Pattern MONTH_SHEET = Pattern.compile("^\\s*([A-Za-z]+)\\.?\\s+(\\d{4})\\s*$");
    private static final String[] MONTH_PREFIXES =
            {"jan", "feb", "mar", "apr", "may", "jun", "jul", "aug", "sep", "oct", "nov", "dec"};
    /** The customers label: "# ลูกค้า", with or without the space. */
    private static final Pattern CUSTOMERS_LABEL = Pattern.compile("^#\\s*ลูกค้า");
    private static final String RESPONSES_LABEL = "ประเมินผล";
    private static final String NOT_EVALUATED_LABEL = "ไม่ได้ประเมินผล";
    private static final String TOP_BOX_LABEL = "top box";
    /** The question rows are numbered "1." to "5." in the first column. */
    private static final Pattern QUESTION_LABEL = Pattern.compile("^\\d+\\.?$");

    public CsatWorkbook parse(String fileName, Instant lastModified, InputStream in) throws IOException {
        List<String> warnings = new ArrayList<>();
        Map<YearMonth, MonthAggregate> months = new LinkedHashMap<>();
        CsatStream stream = null;
        try (Workbook workbook = WorkbookFactory.create(in)) {
            for (Sheet sheet : workbook) {
                Optional<YearMonth> month = monthOf(sheet.getSheetName());
                if (month.isEmpty()) {
                    continue;
                }
                if (stream == null) {
                    stream = CsatStream.detect(titleOf(sheet)).orElse(null);
                }
                try {
                    MonthAggregate aggregate = parseMonth(sheet, month.get());
                    MonthAggregate previous = months.put(month.get(), aggregate);
                    if (previous != null) {
                        warnings.add(fileName + ": two sheets for " + month.get() + "; using '" + sheet.getSheetName() + "'");
                    }
                } catch (SheetLayoutException e) {
                    warnings.add(fileName + " sheet '" + sheet.getSheetName() + "' skipped: " + e.getMessage());
                }
            }
        }
        if (stream == null) {
            stream = CsatStream.detect(fileName).orElse(null);
        }
        if (stream == null) {
            warnings.add(fileName + ": cannot tell which survey this is from its sheet titles or file name; ignored");
        }
        return new CsatWorkbook(fileName, lastModified, stream, months, warnings);
    }

    /** The sheet's title is its first non-empty cell: "Post-MA CSAT Survey". */
    private static String titleOf(Sheet sheet) {
        for (Row row : sheet) {
            for (Cell cell : row) {
                String text = text(cell);
                if (text != null && !text.isEmpty()) {
                    return text;
                }
            }
        }
        return null;
    }

    static Optional<YearMonth> monthOf(String sheetName) {
        Matcher m = MONTH_SHEET.matcher(sheetName);
        if (!m.matches()) {
            return Optional.empty();
        }
        String word = m.group(1).toLowerCase(Locale.ROOT);
        for (int i = 0; i < MONTH_PREFIXES.length; i++) {
            if (word.startsWith(MONTH_PREFIXES[i])) {
                return Optional.of(YearMonth.of(Integer.parseInt(m.group(2)), i + 1));
            }
        }
        return Optional.empty();
    }

    private static MonthAggregate parseMonth(Sheet sheet, YearMonth month) {
        Integer customers = null;
        Integer responses = null;
        Integer notEvaluated = null;
        Double topBox = null;
        int headerRow = -1;
        int fivesColumn = -1;

        for (Row row : sheet) {
            for (Cell cell : row) {
                String text = text(cell);
                if (text != null) {
                    if (customers == null && CUSTOMERS_LABEL.matcher(text).find()) {
                        // The same word heads the rating columns lower down, where
                        // nothing numeric follows it on the row — that one is not a tally.
                        customers = numberRightOf(row, cell).map(Double::intValue).orElse(null);
                    } else if (responses == null && text.equals(RESPONSES_LABEL)) {
                        responses = numberRightOf(row, cell).map(Double::intValue).orElse(null);
                    } else if (notEvaluated == null && text.startsWith(NOT_EVALUATED_LABEL)) {
                        notEvaluated = numberRightOf(row, cell).map(Double::intValue).orElse(null);
                    } else if (topBox == null && text.toLowerCase(Locale.ROOT).equals(TOP_BOX_LABEL)) {
                        topBox = numberRightOf(row, cell).orElse(null);
                    }
                }
                if (headerRow < 0 && isRatingHeader(row, cell)) {
                    headerRow = row.getRowNum();
                    fivesColumn = cell.getColumnIndex();
                }
            }
        }

        if (customers == null || responses == null) {
            throw new SheetLayoutException("customer or response tally not found");
        }
        if (topBox == null) {
            throw new SheetLayoutException("no Top Box cell found");
        }
        if (headerRow < 0) {
            throw new SheetLayoutException("no 5/4/3/2/1 rating header found");
        }
        // The cell is a fraction formatted as a percentage; tolerate one typed as "83".
        if (topBox > 1) {
            topBox = topBox / 100;
        }

        // Question rows: I = ratings of 5, N (five columns right) = ratings given.
        int fives = 0;
        int ratings = 0;
        for (Row row : sheet) {
            if (row.getRowNum() <= headerRow || !isQuestionRow(row, fivesColumn)) {
                continue;
            }
            fives += number(row.getCell(fivesColumn)).map(Double::intValue).orElse(0);
            ratings += number(row.getCell(fivesColumn + 5)).map(Double::intValue).orElse(0);
        }
        return new MonthAggregate(month, customers, responses, notEvaluated == null ? 0 : notEvaluated,
                topBox, fives, ratings);
    }

    /** A cell holding 5 with 4, 3, 2, 1 in the four cells after it. */
    private static boolean isRatingHeader(Row row, Cell cell) {
        for (int i = 0; i < 5; i++) {
            Optional<Double> n = number(row.getCell(cell.getColumnIndex() + i));
            if (n.isEmpty() || Math.abs(n.get() - (5 - i)) > 0.0001) {
                return false;
            }
        }
        return true;
    }

    /** A row whose label, somewhere left of the rating columns, is "1.", "2." … */
    private static boolean isQuestionRow(Row row, int ratingColumn) {
        for (Cell cell : row) {
            if (cell.getColumnIndex() >= ratingColumn) {
                return false;
            }
            String text = text(cell);
            if (text != null && !text.isEmpty()) {
                return QUESTION_LABEL.matcher(text).matches();
            }
            Optional<Double> n = number(cell);
            if (n.isPresent()) {
                return n.get() == Math.floor(n.get()) && n.get() >= 1 && n.get() <= 20;
            }
        }
        return false;
    }

    private static Optional<Double> numberRightOf(Row row, Cell from) {
        for (int c = from.getColumnIndex() + 1; c <= row.getLastCellNum(); c++) {
            Optional<Double> n = number(row.getCell(c));
            if (n.isPresent()) {
                return n;
            }
        }
        return Optional.empty();
    }

    /** String content, trimmed; null for anything that is not text. */
    private static String text(Cell cell) {
        if (cell == null) {
            return null;
        }
        CellType type = cell.getCellType() == CellType.FORMULA ? cell.getCachedFormulaResultType() : cell.getCellType();
        return type == CellType.STRING ? cell.getStringCellValue().strip() : null;
    }

    /** Numeric content, including a formula's cached result and digits typed as text. */
    private static Optional<Double> number(Cell cell) {
        if (cell == null) {
            return Optional.empty();
        }
        CellType type = cell.getCellType() == CellType.FORMULA ? cell.getCachedFormulaResultType() : cell.getCellType();
        if (type == CellType.NUMERIC) {
            return Optional.of(cell.getNumericCellValue());
        }
        if (type == CellType.STRING) {
            String s = cell.getStringCellValue().strip();
            if (s.matches("-?\\d+(\\.\\d+)?")) {
                return Optional.of(Double.parseDouble(s));
            }
        }
        return Optional.empty();
    }

    private static final class SheetLayoutException extends RuntimeException {
        SheetLayoutException(String message) {
            super(message);
        }
    }
}
