package com.raaspal.robotrecommendation.casereport.service;

import com.raaspal.robotrecommendation.casereport.dto.CaseReportRow;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * Builds the On Hold sheet: every held case from both boards, except the airports'.
 *
 * <p>The one sheet that reads two boards, and it does so without a column map of its own.
 * Each board's generator owns its ids — {@code text} is Main Issue on cleaning and Solution
 * on delivery, which is the whole reason the maps are not shared — so this class asks each
 * for its held rows and only joins them. The join is the sheet: a row tagged with the board
 * it came from, so the reviewer can filter to one board and the ticket link opens the
 * right one.
 *
 * <p>Held cases are kept off Cleaning, Makro and Delivery, so a case is here or there,
 * never both. MK keeps its held cases by request, so a held MK case is here <em>and</em>
 * on its own sheet.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class OnHoldReportGenerator {

    private final CleaningPendingReportGenerator cleaning;
    private final MkPendingReportGenerator delivery;

    public List<CaseReportRow> generate(LocalDate asOf) {
        List<CaseReportRow> unordered = new ArrayList<>();
        for (CaseReportRow row : cleaning.generate(CleaningPendingReportGenerator.Scope.ON_HOLD, asOf)) {
            unordered.add(row.withBoard(CaseReportRow.BOARD_CLEANING));
        }
        int fromCleaning = unordered.size();
        for (CaseReportRow row : delivery.generate(MkPendingReportGenerator.Scope.ON_HOLD, asOf)) {
            unordered.add(row.withBoard(CaseReportRow.BOARD_DELIVERY));
        }

        // Oldest first across both boards, as every other sheet is ordered. Not grouped
        // by board: that is what the filter is for, and a reader looking at "All" wants
        // the case that has waited longest at the top whichever board it is on.
        unordered.sort(Comparator.comparing(CaseReportRow::openDate,
                Comparator.nullsLast(Comparator.naturalOrder())));

        List<CaseReportRow> rows = new ArrayList<>(unordered.size());
        for (CaseReportRow row : unordered) {
            rows.add(row.withNo(rows.size() + 1));
        }

        log.info("On Hold report for {}: {} rows ({} cleaning, {} delivery)",
                asOf, rows.size(), fromCleaning, rows.size() - fromCleaning);
        return rows;
    }
}
