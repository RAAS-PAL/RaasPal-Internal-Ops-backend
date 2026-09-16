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
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Builds the pending-case sheets that come from the delivery board: MK, Delivery, and
 * the delivery half of On Hold.
 *
 * <p>MK was the first and is the reason the class is named as it is. Delivery (added
 * 2026-09-16) is its mirror — every other customer on the board, under the same SLA rule,
 * because the board has one Province column and one meaning for it whoever the customer
 * is. Delivery drops its held cases to the On Hold sheet as the cleaning sheets do; MK
 * alone keeps them, because the RE team asked for MK to stay exactly as it was. A held MK
 * case is therefore on two sheets, its own and On Hold, and that is by request.
 *
 * <p>Reads monday live rather than the snapshot tables. Deliberate for now: it makes the
 * report inspectable before the daily sync exists, and the columns it needs are all on the
 * board at 100% fill.
 *
 * <p><strong>Solution is written by the model, not read from the board.</strong> The board
 * has a column for it and nobody fills it — empty on seven tickets in eight. What the
 * report actually prints is a human paraphrase of the comment thread, one dated line per
 * update, which was verified against the 09 September 2026 workbook. So the thread goes
 * to {@link SolutionLineWriter} and the result is what the reviewer sees. A value
 * somebody did type into the board column wins over the model, since a human wrote it.
 *
 * <p><strong>The column ids are hard-coded here on purpose.</strong> The two boards use the
 * same ids for different fields — {@code text} is Solution on delivery but Main Issue on
 * cleaning, and {@code status_1} is Sup Status here and Issue Level there. A shared mapping
 * would be a single place to get one board's meaning wrong for both. Each generator owning
 * its own map is the decision the design records, and is why an AOT change cannot break MK.
 *
 * <p>Board and group ids will move to {@code case_report_definition} once that table is
 * populated; they sit here so this is runnable today.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class MkPendingReportGenerator {

    /** Which of the board's customers a sheet is for. */
    public enum Scope {
        /** MK, Yayoi and Bonus Suki — held cases included, by request. */
        MK,
        /** Every customer that is not MK's, minus the held ones. */
        OTHER,
        /** Every held case on the board, whoever the customer. */
        ON_HOLD
    }

    /** Delivery Tickets. */
    static final String BOARD_ID = "1647612496";

    /** "All Case" — the archives and customer buckets in the other groups are not reports. */
    static final String GROUP_ID = "group_title";

    // Verified against the board's own column titles on 2026-09-10.
    private static final String C_PROJECT = "asset_owner";           // Project/โครงการ
    private static final String C_BRANCH = "text6";                  // Branch
    private static final String C_BRANCH_CODE = "tags2";             // Branch code
    private static final String C_ROBOT = "status_139";              // Model Robot
    private static final String C_SERIAL = "tags42";                 // Serial Number of Robot
    private static final String C_PROBLEM = "main_issue_key_word3";  // Main Issue Key Word
    private static final String C_SOLUTION = "text";                 // Solution
    private static final String C_OPEN_DATE = "date5";               // Open Date
    private static final String C_RE_ON_SITE = "date_18";            // RE Action
    private static final String C_PROVINCE = "color_mm6mwh74";       // Province
    private static final String C_STATUS = "status";                 // Status
    private static final String C_SUP_STATUS = "status_1";           // Sup Status
    private static final String C_UNIT = "text_mksgzhzr";            // Unit — see branchCode()

    private static final List<String> COLUMN_IDS = List.of(
            C_PROJECT, C_BRANCH, C_BRANCH_CODE, C_ROBOT, C_SERIAL, C_PROBLEM,
            C_SOLUTION, C_OPEN_DATE, C_RE_ON_SITE, C_PROVINCE, C_STATUS, C_SUP_STATUS,
            C_UNIT);

    /**
     * A branch code as the customer writes them: one letter and three digits — M154,
     * Y084, K036. The letter is the brand (MK, Yayoi, Bonus Suki), which is why the sheet
     * prints the code beside the branch name.
     */
    private static final Pattern BRANCH_CODE = Pattern.compile("\\b([A-Z]\\d{3})\\b");

    /**
     * The customer this report covers.
     *
     * <p>Three brands, one customer: MK Restaurant Group operates Yayoi and Bonus Suki as
     * well, which is why one SLA covers all three and why the branch prefixes M###, Y###
     * and K### appear together on one sheet.
     *
     * <p>Matched as a substring because the board's values carry a {@code #} prefix and
     * occasional trailing text.
     */
    private static final List<String> PROJECTS = List.of("mk", "yayoi", "bonus suki", "bonussuki");

    /** 3 days inside the six greater-Bangkok provinces. */
    private static final int SLA_METRO = 3;

    /** 5 days anywhere else. */
    private static final int SLA_UPCOUNTRY = 5;

    private final MondayBoardReader boardReader;
    private final SlaCalculator slaCalculator;
    private final SolutionLineWriter solutions;

    /**
     * @param asOf the business date the report is for; every day count is relative to it,
     *             so passing it in rather than reading the clock is what lets yesterday's
     *             report be regenerated identically
     */
    public List<CaseReportRow> generate(Scope scope, LocalDate asOf) {
        List<MondayItem> items = boardReader.readGroupItems(BOARD_ID, GROUP_ID, COLUMN_IDS);

        List<CaseReportRow> unordered = new ArrayList<>();
        int otherCustomers = 0;
        int notYetOpen = 0;
        int notHeld = 0;

        for (MondayItem item : items) {
            String project = stripHash(item.columnText(C_PROJECT));
            if (!belongsTo(scope, project)) {
                otherCustomers++;
                continue;
            }

            LocalDate openDate = MondayCells.date(item.columnText(C_OPEN_DATE));

            // A case that had not been opened yet is not on that day's report.
            //
            // Only bites when regenerating an earlier date, which is exactly when it
            // matters: the board is read live, so yesterday's report would otherwise pick
            // up today's tickets, print a negative day count, and — because a negative
            // number is below every threshold — label them "Within SLA". A confident
            // green row for a case that did not exist is worse than a missing one.
            //
            // A null open date is kept: the case exists, we simply cannot date it, and the
            // SLA comes out blank so somebody fills it in.
            if (openDate != null && openDate.isAfter(asOf)) {
                notYetOpen++;
                continue;
            }

            String province = item.columnText(C_PROVINCE);

            SlaStatus sla = slaCalculator.evaluate(
                    item.columnText(C_STATUS),
                    item.columnText(C_SUP_STATUS),
                    province,
                    openDate,
                    asOf,
                    SLA_METRO,
                    SLA_UPCOUNTRY);

            // Held cases go to On Hold and leave Delivery; MK keeps its own by request.
            boolean held = sla == SlaStatus.ON_HOLD;
            if ((scope == Scope.ON_HOLD && !held) || (scope == Scope.OTHER && held)) {
                notHeld++;
                continue;
            }

            unordered.add(CaseReportRow.of(
                    0,
                    project,
                    branchLabel(item),
                    item.columnText(C_ROBOT),
                    item.columnText(C_SERIAL),
                    item.columnText(C_PROBLEM),
                    solutions.write(item, item.columnText(C_SOLUTION), branchLabel(item),
                            item.columnText(C_PROBLEM), item.columnText(C_STATUS),
                            item.columnText(C_SUP_STATUS), openDate, asOf),
                    openDate,
                    MondayCells.date(item.columnText(C_RE_ON_SITE)),
                    openDate == null ? null : SlaCalculator.daysOpen(openDate, asOf),
                    sla,
                    province,
                    item.id()));
        }

        // Oldest case first, as the team's own sheet is ordered: the rows most overdue are
        // the ones the reader wants at the top. A case with no open date goes last, where
        // its blank SLA is also a prompt to fill the date in.
        unordered.sort(Comparator.comparing(CaseReportRow::openDate,
                Comparator.nullsLast(Comparator.naturalOrder())));

        List<CaseReportRow> rows = new ArrayList<>(unordered.size());
        for (CaseReportRow row : unordered) {
            rows.add(row.withNo(rows.size() + 1));
        }

        log.info("{} pending report for {}: {} rows from {} tickets on the delivery board "
                        + "({} other customers, {} held/not held for this sheet, "
                        + "{} not yet open on that date)",
                scope, asOf, rows.size(), items.size(), otherCustomers, notHeld, notYetOpen);

        return rows;
    }

    /**
     * "M154 โลตัส จันทบุรี" — the code and the name, as the sheet joins them.
     *
     * <p>The code is missing on roughly half the tickets, so the name has to stand alone
     * rather than print with a leading space where the code would have been.
     */
    private static String branchLabel(MondayItem item) {
        String code = branchCode(item);
        String name = item.columnText(C_BRANCH);
        if (code == null) return name;
        if (name == null) return code;
        return code + " " + name;
    }

    /**
     * The branch code, from wherever the ticket carries it.
     *
     * <p>The Branch code tag is the proper home and is empty on half the open tickets. On
     * every one of those, checked 2026-09-11, the code is in the free-text Unit column
     * ("M442", or "MK M453") or in the item name ("Pudu Bot : M066 : MK สาขา …"), and
     * the team fills it in by hand on their sheet. Reading it from there means the sheet
     * matches without anyone retyping it. Only the tag is trusted as-is; the two fallbacks
     * are searched for the code's shape, since the Unit cell also holds things like
     * "Pudu 2".
     */
    private static String branchCode(MondayItem item) {
        String tagged = item.columnText(C_BRANCH_CODE);
        if (tagged != null) return tagged;
        for (String candidate : new String[] { item.columnText(C_UNIT), item.name() }) {
            if (candidate == null) continue;
            Matcher m = BRANCH_CODE.matcher(candidate);
            if (m.find()) return m.group(1);
        }
        return null;
    }

    /**
     * The board writes the customer as "#MK", "#Yayoi", "#BBQ Plaza".
     *
     * <p>Stripped rather than left in place: the hash is a monday tagging artefact, and it
     * is not on the sheet the customer reads.
     */
    private static String stripHash(String project) {
        if (project == null) return null;
        String trimmed = project.trim();
        return trimmed.startsWith("#") ? trimmed.substring(1).trim() : trimmed;
    }

    private static boolean isMk(String project) {
        if (project == null) return false;
        String lower = project.toLowerCase(Locale.ROOT);
        return PROJECTS.stream().anyMatch(lower::contains);
    }

    /**
     * By customer only. A ticket with no Project tag is not MK's — nothing says it is —
     * so it lands on Delivery, where a blank Project cell is a prompt to fill the tag in
     * rather than a case that quietly vanished from every sheet.
     */
    private static boolean belongsTo(Scope scope, String project) {
        return switch (scope) {
            case MK -> isMk(project);
            case OTHER -> !isMk(project);
            case ON_HOLD -> true;
        };
    }
}
