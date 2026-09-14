package com.raaspal.robotrecommendation.casereport.service;

import com.raaspal.robotrecommendation.casereport.adapters.monday.MondayBoardReader;
import com.raaspal.robotrecommendation.casereport.adapters.monday.dto.MondayItem;
import com.raaspal.robotrecommendation.casereport.dto.CaseReportRow;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;

/**
 * Builds the two pending-case sheets that come from the cleaning board: Cleaning and
 * Makro.
 *
 * <p>One board, two sheets, because the RE team splits it by customer. Makro is a big
 * enough account to get its own file, and the airports (AOTGA) get another with a
 * different layout altogether. What is left is "Cleaning". The three sets do not overlap,
 * so a Makro ticket never appears on the Cleaning sheet — a reviewer cannot remove a board
 * row from a report, and thirteen rows to ignore every morning would be worse than none.
 *
 * <p><strong>The column ids here belong to the cleaning board and to no other.</strong>
 * {@code text} is Main Issue on this board but Solution on delivery; {@code status_1} is
 * Issue Level here and Sup Status there. See {@link MkPendingReportGenerator} for why each
 * generator owns its own map.
 *
 * <p>The SLA is 3 days everywhere: no province on this board, and the calculator never
 * consults one when both thresholds are equal.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class CleaningPendingReportGenerator {

    /** Which of the board's customers a sheet is for. */
    public enum Scope {
        /** Every open cleaning case that is not Makro's and not an airport's. */
        CLEANING,
        /** Makro's cases only. */
        MAKRO
    }

    /** Cleaning Tickets. */
    static final String BOARD_ID = "3451717331";

    /** "All Case". */
    static final String GROUP_ID = "new_group96592__1";

    // Verified against the board's own column titles on 2026-09-13.
    private static final String C_PROJECT = "asset_owner3__1";   // Project/โครงการ (tags)
    private static final String C_BRANCH = "text6";              // Branch Name
    private static final String C_ROBOT = "status_17";           // Type of Robot
    private static final String C_SERIAL = "text0";              // Serial Number of Robot
    private static final String C_PROBLEM = "text";              // Main Issue Key Word
    private static final String C_SOLUTION = "long_text";        // Solution วิธีการแก้ไขปัญหา
    private static final String C_OPEN_DATE = "date8";           // Open Date
    private static final String C_RE_ACTION = "date_1";          // RE Action
    private static final String C_STATUS = "status";             // Status
    private static final String C_SUP_STATUS = "status7";        // Sup Status

    private static final List<String> COLUMN_IDS = List.of(
            C_PROJECT, C_BRANCH, C_ROBOT, C_SERIAL, C_PROBLEM, C_SOLUTION,
            C_OPEN_DATE, C_RE_ACTION, C_STATUS, C_SUP_STATUS);

    /**
     * How a Makro ticket is recognised.
     *
     * <p>The Project tag is blank on half the open tickets, so the branch name is checked
     * as well: "Makro สาขา : หนองคาย" with no tag is still Makro. Case-insensitive because
     * the board has "Makro", "MaKro" and "Makroหาดใหญ่".
     */
    private static final List<String> MAKRO = List.of("makro");

    /** 3 days everywhere — same number twice, so the province is never consulted. */
    private static final int SLA_DAYS = 3;

    private final MondayBoardReader boardReader;
    private final SlaCalculator slaCalculator;
    private final SolutionLineWriter solutions;

    public List<CaseReportRow> generate(Scope scope, LocalDate asOf) {
        List<MondayItem> items = boardReader.readGroupItems(BOARD_ID, GROUP_ID, COLUMN_IDS);

        List<CaseReportRow> unordered = new ArrayList<>();
        int otherSheets = 0;
        int notYetOpen = 0;

        for (MondayItem item : items) {
            if (!belongsTo(scope, item)) {
                otherSheets++;
                continue;
            }

            LocalDate openDate = MondayCells.date(item.columnText(C_OPEN_DATE));
            // See MkPendingReportGenerator for why a case opened after asOf is left out.
            if (openDate != null && openDate.isAfter(asOf)) {
                notYetOpen++;
                continue;
            }

            SlaStatus sla = slaCalculator.evaluate(
                    item.columnText(C_STATUS),
                    item.columnText(C_SUP_STATUS),
                    null,
                    openDate,
                    asOf,
                    SLA_DAYS,
                    SLA_DAYS);

            String site = siteLabel(item);

            // The Cleaning sheet prints the customer under "Project" and has no Branch
            // column; the Makro sheet prints the branch under "Branch" and the customer is
            // the sheet itself. The row carries both fields, so each sheet fills the one it
            // shows and the review table hides the other.
            String project = scope == Scope.MAKRO ? "Makro" : site;
            String branch = scope == Scope.MAKRO ? site : null;

            unordered.add(CaseReportRow.of(
                    0,
                    project,
                    branch,
                    item.columnText(C_ROBOT),
                    item.columnText(C_SERIAL),
                    item.columnText(C_PROBLEM),
                    solutions.write(item, item.columnText(C_SOLUTION), site,
                            item.columnText(C_PROBLEM), item.columnText(C_STATUS),
                            item.columnText(C_SUP_STATUS), asOf),
                    openDate,
                    MondayCells.date(item.columnText(C_RE_ACTION)),
                    openDate == null ? null : SlaCalculator.daysOpen(openDate, asOf),
                    sla,
                    null,
                    item.id()));
        }

        unordered.sort(Comparator.comparing(CaseReportRow::openDate,
                Comparator.nullsLast(Comparator.naturalOrder())));

        List<CaseReportRow> rows = new ArrayList<>(unordered.size());
        for (CaseReportRow row : unordered) {
            rows.add(row.withNo(rows.size() + 1));
        }

        log.info("{} pending report for {}: {} rows from {} tickets on the cleaning board "
                        + "({} belong to other sheets, {} not yet open on that date)",
                scope, asOf, rows.size(), items.size(), otherSheets, notYetOpen);

        return rows;
    }

    /**
     * The customer as the sheet names it: the branch name, which is filled on every
     * ticket, rather than the Project tag, which is blank on half of them. "One Bangkok",
     * "โรงพยาบาลเซนต์หลุยส์", "Makro สาขา : หนองคาย".
     */
    private static String siteLabel(MondayItem item) {
        String branch = MondayCells.text(item.columnText(C_BRANCH));
        if (branch != null) return branch;
        return MondayCells.text(item.columnText(C_PROJECT));
    }

    private static boolean belongsTo(Scope scope, MondayItem item) {
        String haystack = ((item.columnText(C_PROJECT) == null ? "" : item.columnText(C_PROJECT))
                + " " + (item.columnText(C_BRANCH) == null ? "" : item.columnText(C_BRANCH)))
                .toLowerCase(Locale.ROOT);
        boolean makro = MAKRO.stream().anyMatch(haystack::contains);
        return switch (scope) {
            case MAKRO -> makro;
            case CLEANING -> !makro && !AirportTickets.matches(
                    item.columnText(C_PROJECT), item.columnText(C_BRANCH));
        };
    }
}
