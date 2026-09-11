package com.raaspal.robotrecommendation.casereport.service;

import com.raaspal.robotrecommendation.casereport.adapters.monday.MondayBoardReader;
import com.raaspal.robotrecommendation.ai.service.CaseSolutionAiService;
import com.raaspal.robotrecommendation.casereport.adapters.monday.dto.MondayItem;
import com.raaspal.robotrecommendation.casereport.adapters.monday.dto.MondayUpdate;
import com.raaspal.robotrecommendation.casereport.dto.CaseProgressRequest;
import com.raaspal.robotrecommendation.casereport.dto.CaseReportRow;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.time.LocalDate;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Builds the MK pending-case sheet from the delivery board.
 *
 * <p>Reads monday live rather than the snapshot tables. Deliberate for now: it makes the
 * report inspectable before the daily sync exists, and the columns it needs are all on the
 * board at 100% fill.
 *
 * <p><strong>Solution is written by the model, not read from the board.</strong> The board
 * has a column for it and nobody fills it — empty on seven tickets in eight. What the
 * report actually prints is a human paraphrase of the comment thread, one dated line per
 * update, which was verified against the 09 September 2026 workbook. So the thread goes
 * to {@link CaseSolutionAiService} and the result is what the reviewer sees. A value
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

    private static final List<String> COLUMN_IDS = List.of(
            C_PROJECT, C_BRANCH, C_BRANCH_CODE, C_ROBOT, C_SERIAL, C_PROBLEM,
            C_SOLUTION, C_OPEN_DATE, C_RE_ON_SITE, C_PROVINCE, C_STATUS, C_SUP_STATUS);

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
    private final CaseSolutionAiService solutionAi;

    /**
     * @param asOf the business date the report is for; every day count is relative to it,
     *             so passing it in rather than reading the clock is what lets yesterday's
     *             report be regenerated identically
     */
    public List<CaseReportRow> generate(LocalDate asOf) {
        List<MondayItem> items = boardReader.readGroupItems(BOARD_ID, GROUP_ID, COLUMN_IDS);

        List<CaseReportRow> rows = new ArrayList<>();
        int otherCustomers = 0;
        int notYetOpen = 0;

        for (MondayItem item : items) {
            String project = stripHash(item.columnText(C_PROJECT));
            if (!belongsToThisReport(project)) {
                otherCustomers++;
                continue;
            }

            LocalDate openDate = parseDate(item.columnText(C_OPEN_DATE));

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

            rows.add(CaseReportRow.of(
                    rows.size() + 1,
                    project,
                    branchLabel(item),
                    item.columnText(C_ROBOT),
                    item.columnText(C_SERIAL),
                    item.columnText(C_PROBLEM),
                    solutionFor(item, asOf),
                    openDate,
                    parseDate(item.columnText(C_RE_ON_SITE)),
                    openDate == null ? null : SlaCalculator.daysOpen(openDate, asOf),
                    sla,
                    province,
                    item.id()));
        }

        log.info("MK pending report for {}: {} rows from {} tickets on the delivery board "
                        + "({} other customers, {} not yet open on that date)",
                asOf, rows.size(), items.size(), otherCustomers, notYetOpen);

        return rows;
    }

    /**
     * The Solution line: the board's own column if a human filled it, else the model's
     * paraphrase of the comment thread.
     *
     * <p>The contact centre's intake form is dropped before the thread goes to the
     * model. It is the first comment on nearly every ticket, it begins "Ticket ID", and
     * it describes the request rather than any step taken on it — so it is both noise
     * and, at several hundred characters, most of the tokens.
     */
    private String solutionFor(MondayItem item, LocalDate asOf) {
        String typed = item.columnText(C_SOLUTION);
        if (typed != null && !typed.isBlank()) {
            return typed;
        }
        if (item.updates() == null || item.updates().isEmpty()) {
            return null;
        }

        List<CaseProgressRequest.Comment> comments = new ArrayList<>();
        // monday returns newest first; the line is written oldest first.
        for (int i = item.updates().size() - 1; i >= 0; i--) {
            MondayUpdate u = item.updates().get(i);
            String body = u.textBody() == null ? "" : u.textBody().strip();
            if (body.isEmpty() || isIntakeForm(body)) continue;
            comments.add(new CaseProgressRequest.Comment(
                    u.createdAt() == null ? null : u.createdAt().toLocalDate(),
                    u.creatorName(),
                    body));
        }
        if (comments.isEmpty()) {
            return null;
        }

        String line = solutionAi.summariseProgress(new CaseProgressRequest(
                branchLabel(item),
                item.columnText(C_PROBLEM),
                item.columnText(C_STATUS),
                item.columnText(C_SUP_STATUS),
                asOf,
                comments));
        if (line == null || line.isBlank()) return null;
        // The one rule the model is allowed to break and the report is not.
        return SolutionLine.splitCrossMonthRanges(line, asOf.getYear());
    }

    private static boolean isIntakeForm(String body) {
        return body.startsWith("Ticket ID");
    }

    /**
     * "M154 โลตัส จันทบุรี" — the code and the name, as the sheet joins them.
     *
     * <p>The code is missing on roughly half the tickets, so the name has to stand alone
     * rather than print with a leading space where the code would have been.
     */
    private static String branchLabel(MondayItem item) {
        String code = item.columnText(C_BRANCH_CODE);
        String name = item.columnText(C_BRANCH);
        if (code == null) return name;
        if (name == null) return code;
        return code + " " + name;
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

    private static boolean belongsToThisReport(String project) {
        if (project == null) return false;
        String lower = project.toLowerCase(Locale.ROOT);
        return PROJECTS.stream().anyMatch(lower::contains);
    }

    /**
     * A monday date column, or null.
     *
     * <p>Lenient by design. An unparseable date leaves the day count and the SLA blank,
     * which surfaces the row for a human; throwing would fail the whole report over one
     * malformed cell, on a board people edit by hand all day.
     */
    private static LocalDate parseDate(String text) {
        if (text == null || text.isBlank()) return null;
        try {
            return LocalDate.parse(text.trim());
        } catch (DateTimeParseException e) {
            log.warn("Unparseable date on the delivery board: '{}'", text);
            return null;
        }
    }
}
