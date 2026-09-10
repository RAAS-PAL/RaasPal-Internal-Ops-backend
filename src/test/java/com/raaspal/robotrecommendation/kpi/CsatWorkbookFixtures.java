package com.raaspal.robotrecommendation.kpi;

import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.UncheckedIOException;

/**
 * Builds survey workbooks laid out the way the RE team lays theirs out — the
 * tallies at the top, the five-question table with a column per rating, the
 * sheet's own Overall CSAT and Top Box below — so the parser is tested against
 * the real shape without a real workbook (which names customers) in the repo.
 */
final class CsatWorkbookFixtures {

    private CsatWorkbookFixtures() {
    }

    /**
     * One month sheet.
     *
     * @param perQuestion  one row per question: ratings of 5, 4, 3, 2, 1 in that order
     * @param topBoxCell   what to write in the sheet's Top Box cell; null = the true value
     */
    record MonthSpec(String sheetName, int customers, int responses, int notEvaluated,
                     int[][] perQuestion, Double topBoxCell) {

        static MonthSpec of(String sheetName, int customers, int responses, int[]... perQuestion) {
            return new MonthSpec(sheetName, customers, responses, customers - responses, perQuestion, null);
        }

        MonthSpec withTopBoxCell(double value) {
            return new MonthSpec(sheetName, customers, responses, notEvaluated, perQuestion, value);
        }
    }

    static byte[] workbook(String title, MonthSpec... months) {
        try (XSSFWorkbook wb = new XSSFWorkbook()) {
            // Sheets the parser must ignore, in the positions the real files have them.
            Sheet summary = wb.createSheet("Summary");
            summary.createRow(0).createCell(1).setCellValue(title);
            Sheet detail = wb.createSheet("Case_Detail Jan-Aug 2026");
            detail.createRow(0).createCell(1).setCellValue("(per-case rows the parser never reads)");
            Sheet yearly = wb.createSheet("Y2026 CSAT");
            yearly.createRow(0).createCell(0).setCellValue(title);
            yearly.createRow(2).createCell(0).setCellValue("Month");

            for (MonthSpec m : months) {
                monthSheet(wb, title, m);
            }
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            wb.write(out);
            return out.toByteArray();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    private static void monthSheet(XSSFWorkbook wb, String title, MonthSpec m) {
        Sheet s = wb.createSheet(m.sheetName());
        s.createRow(0).createCell(0).setCellValue(title);
        s.createRow(1).createCell(0).setCellValue("As of " + m.sheetName());

        Row r3 = s.createRow(3);
        r3.createCell(0).setCellValue("# ลูกค้า");
        r3.createCell(2).setCellValue(m.customers());
        r3.createCell(9).setCellValue("Mean Score =");
        Row r4 = s.createRow(4);
        r4.createCell(0).setCellValue("ประเมินผล");
        r4.createCell(2).setCellValue(m.responses());
        Row r5 = s.createRow(5);
        r5.createCell(0).setCellValue("ไม่ได้ประเมินผล");
        r5.createCell(2).setCellValue(m.notEvaluated());
        r5.createCell(3).setCellValue("(ไม่รับสาย " + m.notEvaluated() + ")");

        Row r7 = s.createRow(7);
        r7.createCell(0).setCellValue("Questionnaire");
        r7.createCell(8).setCellValue("#ลูกค้า"); // the same word, heading the rating columns
        r7.createCell(14).setCellValue("CSAT");
        r7.createCell(15).setCellValue("%");
        Row r8 = s.createRow(8);
        for (int i = 0; i < 5; i++) {
            r8.createCell(8 + i).setCellValue(5 - i);
        }
        r8.createCell(13).setCellValue("Total");

        int[] sums = new int[5];
        int row = 9;
        for (int q = 0; q < m.perQuestion().length; q++) {
            int[] counts = m.perQuestion()[q];
            Row r = s.createRow(row++);
            r.createCell(0).setCellValue((q + 1) + ".");
            r.createCell(1).setCellValue("คำถามข้อ " + (q + 1));
            int total = 0;
            double weighted = 0;
            for (int i = 0; i < 5; i++) {
                if (counts[i] != 0) {
                    r.createCell(8 + i).setCellValue(counts[i]);
                }
                sums[i] += counts[i];
                total += counts[i];
                weighted += (5 - i) * counts[i];
            }
            r.createCell(13).setCellValue(total);
            if (total > 0) {
                r.createCell(14).setCellValue(weighted / total);
                r.createCell(15).setCellValue(weighted / total / 5);
            }
        }
        Row totals = s.createRow(row++);
        int all = 0;
        double weightedAll = 0;
        for (int i = 0; i < 5; i++) {
            totals.createCell(8 + i).setCellValue(sums[i]);
            all += sums[i];
            weightedAll += (5 - i) * sums[i];
        }
        Row overall = s.createRow(row++);
        overall.createCell(13).setCellValue("Overall CSAT");
        if (all > 0) {
            overall.createCell(14).setCellValue(weightedAll / all);
            overall.createCell(15).setCellValue(weightedAll / all / 5);
        }
        Row top = s.createRow(row);
        top.createCell(7).setCellValue("Top Box");
        double topBox = m.topBoxCell() != null ? m.topBoxCell() : (all == 0 ? 0 : (double) sums[0] / all);
        top.createCell(8).setCellValue(topBox);
    }
}
