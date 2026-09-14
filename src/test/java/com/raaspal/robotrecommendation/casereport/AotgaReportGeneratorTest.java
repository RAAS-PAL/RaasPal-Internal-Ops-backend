package com.raaspal.robotrecommendation.casereport;

import com.raaspal.robotrecommendation.ai.service.CasePartsAiService;
import com.raaspal.robotrecommendation.ai.service.CaseSolutionAiService;
import com.raaspal.robotrecommendation.casereport.adapters.monday.MondayBoardReader;
import com.raaspal.robotrecommendation.casereport.adapters.monday.dto.MondayColumnValue;
import com.raaspal.robotrecommendation.casereport.adapters.monday.dto.MondayItem;
import com.raaspal.robotrecommendation.casereport.adapters.monday.dto.MondayUpdate;
import com.raaspal.robotrecommendation.casereport.service.PartsLineWriter;
import com.raaspal.robotrecommendation.casereport.dto.CasePartsSummary;
import com.raaspal.robotrecommendation.casereport.dto.CaseProgressRequest;
import com.raaspal.robotrecommendation.casereport.dto.CaseReportRow;
import com.raaspal.robotrecommendation.casereport.service.AotgaReportGenerator;
import com.raaspal.robotrecommendation.casereport.service.CleaningPendingReportGenerator;
import com.raaspal.robotrecommendation.casereport.service.CleaningPendingReportGenerator.Scope;
import com.raaspal.robotrecommendation.casereport.service.SlaCalculator;
import com.raaspal.robotrecommendation.casereport.service.SlaStatus;
import com.raaspal.robotrecommendation.casereport.service.SolutionLineWriter;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * The RAW_AOTGA sheet: which tickets it claims, how it prints the site, and the two day
 * counts.
 *
 * <p>Dates are shaped like the RE team's 09-Sep-2026 workbook, with {@code asOf} one day
 * later so the exclusive count reproduces their inclusive figures — that workbook counted
 * the open day, the rule since 11 Sep does not, and the difference is exactly one.
 */
class AotgaReportGeneratorTest {

    private static final LocalDate AS_OF = LocalDate.of(2026, 9, 10);

    private final MondayBoardReader boardReader = mock(MondayBoardReader.class);
    private final CasePartsAiService partsAi = mock(CasePartsAiService.class);
    private AotgaReportGenerator generator;
    private CleaningPendingReportGenerator cleaning;

    @BeforeEach
    void board() {
        SlaCalculator sla = new SlaCalculator(List.of("Bangkok"));
        when(partsAi.extractParts(any())).thenReturn(CasePartsSummary.EMPTY);
        generator = new AotgaReportGenerator(boardReader, sla, new PartsLineWriter(partsAi));

        CaseSolutionAiService ai = mock(CaseSolutionAiService.class);
        when(ai.summariseProgress(any())).thenReturn("");
        cleaning = new CleaningPendingReportGenerator(boardReader, sla, new SolutionLineWriter(ai));

        when(boardReader.readGroupItems(any(), any(), any())).thenReturn(List.of(
                // Row 1 of the workbook: opened 14 Jul, part received 22 Jul.
                ticket("1", "AOTGA-DMK M75 : มอเตอร์เกียร์เลี้ยว", "AOTGA-:-DMK", "AOTGA ดอนเมือง", "M75",
                        "2026-07-14", "Potentiometer", "รออะไหล่เสียคืนจาก AOTGA", "AOTGA", "2026-07-22"),
                // Row 7: opened 3 Jul, nothing received yet.
                // Nothing typed on the board; the thread has to say it.
                ticket("7", "AOTGA-BKK M40 : สายเสาโมดูล 4G ขาด", null, "ท่าอากาศยานสุวรรณภูมิ", "M40",
                        "2026-07-03", null, null, null, null),
                // Row 13: the SAT1 site code, four characters.
                ticket("13", "AOTGA-SAT1 M75 : โพเทนเชียล", "AOTGA-SAT1", null, "M75",
                        "2026-09-02", "Potentiometer และ Encoder", "รออะไหล่เสียคืนจาก AOTGA", "AOTGA", "2026-09-04"),
                // Prefix only in the name — the Project tag and branch say nothing.
                ticket("20", "AOTGA-CEI M50 : ท่อน้ำทิ้ง", null, "Terminal 1", "M50",
                        "2026-09-03", "ท่อน้ำเสีย", null, "AOTGA", null),
                // Caught by a Thai keyword, no prefix anywhere: falls back to the branch.
                ticket("21", "ล้อหน้าสั่น", null, "ท่าอากาศยานภูเก็ต", "M50",
                        "2026-09-08", "Motor Wheel", "อยู่ระหว่าง RAASPAL ตรวจสอบ", "RAASPAL", null),
                // Part received after asOf: not received yet on that date.
                ticket("22", "AOTGA-DMK M50 : ล้อ", "AOTGA-DMK", null, "M50",
                        "2026-09-01", "Motor Wheel", null, "AOTGA", "2026-09-12"),
                // Not airports.
                ticket("30", "Makro หาดใหญ่", "Makroหาดใหญ่", "Makro หาดใหญ่", "Omnie",
                        "2026-09-01", null, null, null, null),
                ticket("31", "One Bangkok", "OneBangkok", "One Bangkok", "SpiderBot",
                        "2026-07-16", null, null, null, null),
                // Opened after the report date.
                ticket("40", "AOTGA-BKK M75 : new", "AOTGA-BKK", null, "M75",
                        "2026-09-11", null, null, null, null)));
    }

    @Test
    void claimsTheAirportsAndNothingElseOrderedByOpenDate() {
        List<CaseReportRow> rows = generator.generate(AS_OF);

        assertThat(rows).extracting(CaseReportRow::sourceItemId)
                .containsExactly("7", "1", "22", "13", "20", "21");
        assertThat(rows).extracting(CaseReportRow::no).containsExactly(1, 2, 3, 4, 5, 6);
    }

    /** Normalised from wherever the RE team wrote it; falls back to the branch. */
    @Test
    void printsTheSiteCodeAsTheSheetDoes() {
        Map<String, CaseReportRow> byId = byId();

        assertThat(byId.get("1").project()).isEqualTo("AOTGA-DMK");    // tag "AOTGA-:-DMK"
        assertThat(byId.get("7").project()).isEqualTo("AOTGA-BKK");    // name only
        assertThat(byId.get("13").project()).isEqualTo("AOTGA-SAT1");  // four characters
        assertThat(byId.get("20").project()).isEqualTo("AOTGA-CEI");
        assertThat(byId.get("21").project()).isEqualTo("ท่าอากาศยานภูเก็ต"); // no prefix anywhere
    }

    @Test
    void mapsThePartTrackingColumnsAndTheRobotModel() {
        CaseReportRow row = byId().get("1");

        assertThat(row.robot()).isEqualTo("M75");
        assertThat(row.serialNumber()).isEqualTo("GS-1");
        assertThat(row.requiredPart()).isEqualTo("Potentiometer");
        assertThat(row.waiting()).isEqualTo("รออะไหล่เสียคืนจาก AOTGA");
        assertThat(row.waitingFrom()).isEqualTo("AOTGA");
        assertThat(row.partReceived()).isEqualTo(LocalDate.of(2026, 7, 22));
    }

    /**
     * Both counts leave out their first day. With asOf one day after the workbook's
     * heading, the exclusive figures equal the workbook's inclusive ones: 58 and 50.
     */
    @Test
    void daysAndAgingAreBothCountedExclusively() {
        Map<String, CaseReportRow> byId = byId();

        assertThat(byId.get("1").days()).isEqualTo(58);
        assertThat(byId.get("1").agingAfterReceived()).isEqualTo(50);

        assertThat(byId.get("13").days()).isEqualTo(8);
        assertThat(byId.get("13").agingAfterReceived()).isEqualTo(6);
    }

    @Test
    void agingIsNullUntilAPartIsReceived() {
        Map<String, CaseReportRow> byId = byId();

        assertThat(byId.get("7").partReceived()).isNull();
        assertThat(byId.get("7").agingAfterReceived()).isNull();
    }

    /** A receipt dated after the report date has not, on that date, happened. */
    @Test
    void aPartReceivedAfterTheReportDateIsNotYetReceived() {
        CaseReportRow row = byId().get("22");

        assertThat(row.partReceived()).isEqualTo(LocalDate.of(2026, 9, 12));
        assertThat(row.agingAfterReceived()).isNull();
    }

    /** The columns the sheet does not have stay empty rather than carrying stale values. */
    @Test
    void leavesTheOtherSheetsColumnsEmpty() {
        for (CaseReportRow row : generator.generate(AS_OF)) {
            assertThat(row.branch()).isNull();
            assertThat(row.solution()).isNull();
            assertThat(row.reOnSite()).isNull();
            assertThat(row.province()).isNull();
        }
    }

    /** Computed at 3 days for the review table, though the sheet does not print it. */
    @Test
    void slaIsThreeDaysThoughUnprinted() {
        Map<String, CaseReportRow> byId = byId();

        assertThat(byId.get("1").sla()).isEqualTo(SlaStatus.BREACHED);   // 58 days
        assertThat(byId.get("21").sla()).isEqualTo(SlaStatus.WITHIN);    // 2 days
    }

    @Test
    void aTicketOpenedAfterTheReportDateIsLeftOut() {
        assertThat(generator.generate(AS_OF))
                .extracting(CaseReportRow::sourceItemId).doesNotContain("40");
        assertThat(generator.generate(AS_OF.plusDays(1)))
                .extracting(CaseReportRow::sourceItemId).contains("40");
    }

    /**
     * The accepted asymmetry, pinned: a ticket whose only airport marker is its name is
     * on the AOTGA sheet <em>and</em> still on the Cleaning sheet, because Cleaning's
     * exclusion does not read the name. Duplicated, not lost. Tickets marked in the tag
     * or branch are on exactly one.
     */
    @Test
    void aNameOnlyPrefixLandsOnBothSheetsRatherThanNeither() {
        List<String> aotga = generator.generate(AS_OF).stream()
                .map(CaseReportRow::sourceItemId).toList();
        List<String> cleaningRows = cleaning.generate(Scope.CLEANING, AS_OF).stream()
                .map(CaseReportRow::sourceItemId).toList();

        assertThat(aotga).contains("20");
        assertThat(cleaningRows).contains("20");

        assertThat(cleaningRows).doesNotContain("1", "7", "13", "21", "22");
    }

    /** The board's typed cells win, and the model is not asked for a row that has them all. */
    @Test
    void typedBoardCellsWinAndSkipTheModel() {
        CaseReportRow row = byId().get("1");

        assertThat(row.requiredPart()).isEqualTo("Potentiometer");
        verify(partsAi, never()).extractParts(argThat(r -> "AOTGA-DMK".equals(r.branch())
                && r.comments().stream().anyMatch(c -> c.body().contains("ticket 1"))));
    }

    /** With nothing typed, the thread goes to the model and its answer fills the cells. */
    @Test
    void theThreadFillsWhatTheBoardLeavesBlank() {
        when(partsAi.extractParts(argThat(r -> r != null && "AOTGA-BKK".equals(r.branch()))))
                .thenReturn(new CasePartsSummary(
                        "4G Module Cable", "รออะไหล่มือ 1 จาก Supplier RAASPAL", "Supplier RAASPAL",
                        LocalDate.of(2026, 9, 5)));

        CaseReportRow row = byId().get("7");

        assertThat(row.requiredPart()).isEqualTo("4G Module Cable");
        assertThat(row.waiting()).isEqualTo("รออะไหล่มือ 1 จาก Supplier RAASPAL");
        assertThat(row.waitingFrom()).isEqualTo("Supplier RAASPAL");
        assertThat(row.partReceived()).isEqualTo(LocalDate.of(2026, 9, 5));
        assertThat(row.agingAfterReceived()).isEqualTo(5);
    }

    /** The model is given the thread oldest first, with the intake form dropped. */
    @Test
    void theModelSeesTheThreadOldestFirstWithoutTheIntakeForm() {
        generator.generate(AS_OF);

        verify(partsAi).extractParts(argThat(r -> r != null && "AOTGA-BKK".equals(r.branch())
                && r.comments().size() == 2
                && r.comments().get(0).body().startsWith("สั่งสาย")
                && r.comments().get(1).body().startsWith("ได้รับ")
                && r.asOf().equals(AS_OF)));
    }

    /** A model that settles nothing leaves dashes, not a failed report. */
    @Test
    void anEmptyAnswerLeavesTheCellsBlank() {
        CaseReportRow row = byId().get("7");

        assertThat(row.requiredPart()).isNull();
        assertThat(row.waiting()).isNull();
        assertThat(row.waitingFrom()).isNull();
        assertThat(row.partReceived()).isNull();
        assertThat(row.agingAfterReceived()).isNull();
    }

    private Map<String, CaseReportRow> byId() {
        Map<String, CaseReportRow> byId = new HashMap<>();
        generator.generate(AS_OF).forEach(r -> byId.put(r.sourceItemId(), r));
        return byId;
    }

    private static MondayItem ticket(String id, String name, String project, String branch,
                                     String robot, String open, String part, String waiting,
                                     String waitingFrom, String received) {
        List<MondayColumnValue> cells = new ArrayList<>();
        cells.add(new MondayColumnValue("asset_owner3__1", "tags", project, null));
        cells.add(new MondayColumnValue("text6", "text", branch, null));
        cells.add(new MondayColumnValue("status_17", "status", robot, null));
        cells.add(new MondayColumnValue("text0", "text", "GS-" + id, null));
        cells.add(new MondayColumnValue("text", "text", "ปัญหา " + id, null));
        cells.add(new MondayColumnValue("date8", "date", open, null));
        cells.add(new MondayColumnValue("status", "status", "Pending", null));
        cells.add(new MondayColumnValue("status7", "status", "รออะไหล่", null));
        cells.add(new MondayColumnValue("dropdown_mknqq9fm", "dropdown", part, null));
        cells.add(new MondayColumnValue("text_mm3j1dbd", "text", waiting, null));
        cells.add(new MondayColumnValue("dropdown_mm1gmnst", "dropdown", waitingFrom, null));
        cells.add(new MondayColumnValue("date_mm3b365t", "date", received, null));
        return new MondayItem(id, name, null, null, cells, updatesFor(id), null);
    }

    /** Ticket 7's thread, newest first as monday returns it. Others have none. */
    private static List<MondayUpdate> updatesFor(String id) {
        if (!"7".equals(id)) return List.of();
        return List.of(
                update("u3", "2026-09-05T03:00:00Z", "ได้รับสายเสาโมดูล 4G จาก Supplier แล้ว"),
                update("u2", "2026-07-10T08:00:00Z", "สั่งสายเสาโมดูล 4G กับ Supplier RAASPAL"),
                update("u1", "2026-07-03T02:00:00Z", "Ticket ID 7 - intake form, not a step"));
    }

    private static MondayUpdate update(String id, String createdAt, String body) {
        return new MondayUpdate(id, body, OffsetDateTime.parse(createdAt), null);
    }
}
