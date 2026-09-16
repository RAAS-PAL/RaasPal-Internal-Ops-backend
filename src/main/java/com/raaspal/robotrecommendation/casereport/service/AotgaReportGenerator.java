package com.raaspal.robotrecommendation.casereport.service;

import com.raaspal.robotrecommendation.casereport.adapters.monday.MondayBoardReader;
import com.raaspal.robotrecommendation.casereport.adapters.monday.dto.MondayItem;
import com.raaspal.robotrecommendation.casereport.dto.CasePartsSummary;
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
 * Builds the {@code RAW_AOTGA} sheet: the airports' open cases from the cleaning board.
 *
 * <p>A different report from the other cleaning sheets, not a third scope of the same
 * one. Cleaning and Makro are pending-case lists judged against an SLA; this one tracks
 * <em>spare-part turnaround</em> — what was ordered, who it is waited on, when it arrived
 * and how long ago. It prints no Solution, RE On Site or SLA column, and its five
 * part-tracking columns exist on no other sheet, which is why it has its own class and
 * its own factory on {@link CaseReportRow}.
 *
 * <p>Reads the same "All Case" group the other two do. The board also has groups named
 * "AOTGA" and "AOTGA 30 Credit cases"; they are deliberately not read — the RE team
 * confirmed the sheet is built from All Case only.
 *
 * <p><strong>The column ids here belong to the cleaning board and to no other.</strong>
 * Verified against the board's own column list on 2026-09-14.
 *
 * <p><strong>The part-tracking cells come from the comment thread, not the board.</strong>
 * The board has columns for them, but a count on 2026-09-14 found zero of the twelve
 * open airport tickets with a value in any of them, and every ticket with a thread. So
 * they go through {@link PartsLineWriter}: a typed value still wins, field by field, and
 * the model fills the rest — the same arrangement as the Solution column elsewhere.
 *
 * <p>The SLA is 3 days, as on every cleaning sheet, and is computed so the review table
 * can show it. The sheet itself does not print it.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class AotgaReportGenerator {

    /** Cleaning Tickets. */
    static final String BOARD_ID = CleaningPendingReportGenerator.BOARD_ID;

    /** "All Case". */
    static final String GROUP_ID = CleaningPendingReportGenerator.GROUP_ID;

    private static final String C_PROJECT = "asset_owner3__1";      // Project/โครงการ (tags)
    private static final String C_BRANCH = "text6";                 // Branch Name
    private static final String C_ROBOT = "status_17";              // Type of Robot — M75, M50, M40
    private static final String C_SERIAL = "text0";                 // Serial Number of Robot
    private static final String C_PROBLEM = "text";                 // Main Issue Key Word
    private static final String C_OPEN_DATE = "date8";              // Open Date
    private static final String C_STATUS = "status";                // Status
    private static final String C_SUP_STATUS = "status7";           // Sup Status

    // The board's own parts columns. Read so a typed value wins; empty on every airport
    // ticket as of 2026-09-14, so in practice the thread supplies these.
    private static final String C_REQUIRED_PART = "dropdown_mknqq9fm"; // Spare Parts Name
    private static final String C_WAITING = "text_mm3j1dbd";        // อัพเดทปัจจุบัน
    private static final String C_WAITING_FROM = "dropdown_mm1gmnst"; // อัพเดท
    private static final String C_PART_RECEIVED = "date_mm3b365t";  // วันส่งอะไหล่

    private static final List<String> COLUMN_IDS = List.of(
            C_PROJECT, C_BRANCH, C_ROBOT, C_SERIAL, C_PROBLEM, C_OPEN_DATE, C_STATUS,
            C_SUP_STATUS, C_REQUIRED_PART, C_WAITING, C_WAITING_FROM, C_PART_RECEIVED);

    /** 3 days everywhere — same number twice, so the province is never consulted. */
    private static final int SLA_DAYS = 3;

    /**
     * The site prefix the RE team writes on airport tickets: {@code AOTGA-BKK},
     * {@code AOTGA-SAT1}, and on older tickets {@code AOTGA-:-DMK}. The separator varies;
     * the code after it is two to five letters or digits.
     */
    private static final Pattern SITE_PREFIX =
            Pattern.compile("(?i)AOTGA\\s*[-:]+\\s*([A-Z0-9]{2,5})");

    private final MondayBoardReader boardReader;
    private final SlaCalculator slaCalculator;
    private final PartsLineWriter parts;

    public List<CaseReportRow> generate(LocalDate asOf) {
        List<MondayItem> items = boardReader.readGroupItems(BOARD_ID, GROUP_ID, COLUMN_IDS);

        List<CaseReportRow> unordered = new ArrayList<>();
        int otherSheets = 0;
        int notYetOpen = 0;

        for (MondayItem item : items) {
            if (!AirportTickets.matchesIncludingName(
                    item.name(), item.columnText(C_PROJECT), item.columnText(C_BRANCH))) {
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

            String project = projectLabel(item);
            CasePartsSummary partsCells = parts.write(
                    item,
                    item.columnText(C_REQUIRED_PART),
                    item.columnText(C_WAITING),
                    item.columnText(C_WAITING_FROM),
                    MondayCells.date(item.columnText(C_PART_RECEIVED)),
                    project,
                    item.columnText(C_PROBLEM),
                    item.columnText(C_STATUS),
                    item.columnText(C_SUP_STATUS),
                    asOf);
            LocalDate partReceived = partsCells.partReceived();

            unordered.add(CaseReportRow.ofAotga(
                    0,
                    project,
                    item.columnText(C_ROBOT),
                    item.columnText(C_SERIAL),
                    item.columnText(C_PROBLEM),
                    openDate,
                    openDate == null ? null : SlaCalculator.daysOpen(openDate, asOf),
                    sla,
                    partsCells.requiredPart(),
                    partsCells.waiting(),
                    partsCells.waitingFrom(),
                    partReceived,
                    // Same count as Days: the received day itself is not counted. A part
                    // received after asOf has not, on that date, been received.
                    partReceived == null || partReceived.isAfter(asOf)
                            ? null
                            : SlaCalculator.daysOpen(partReceived, asOf),
                    item.id()));
        }

        unordered.sort(Comparator.comparing(CaseReportRow::openDate,
                Comparator.nullsLast(Comparator.naturalOrder())));

        List<CaseReportRow> rows = new ArrayList<>(unordered.size());
        for (CaseReportRow row : unordered) {
            rows.add(row.withNo(rows.size() + 1));
        }

        log.info("AOTGA report for {}: {} rows from {} tickets on the cleaning board "
                        + "({} belong to other sheets, {} not yet open on that date)",
                asOf, rows.size(), items.size(), otherSheets, notYetOpen);

        return rows;
    }

    /**
     * The site as the sheet prints it: {@code AOTGA-DMK}.
     *
     * <p>Taken from the prefix the RE team writes on the ticket, wherever it appears —
     * the name, the Project tag or the branch — and normalised, so {@code AOTGA-:-DMK}
     * and {@code aotga-dmk} both print as {@code AOTGA-DMK}. A ticket with no prefix
     * (one the airport keywords caught) falls back to the Project tag and then the
     * branch name, so the cell is never blank on a row that has anything to say.
     */
    static String projectLabel(MondayItem item) {
        String project = item.columnText(C_PROJECT);
        String branch = item.columnText(C_BRANCH);

        for (String field : new String[] { item.name(), project, branch }) {
            if (field == null) continue;
            Matcher m = SITE_PREFIX.matcher(field);
            if (m.find()) {
                return "AOTGA-" + m.group(1).toUpperCase(Locale.ROOT);
            }
        }

        String tag = MondayCells.text(project);
        if (tag != null) return tag;
        return MondayCells.text(branch);
    }
}
