package com.raaspal.robotrecommendation.casereport;

import com.raaspal.robotrecommendation.ai.service.CaseSolutionAiService;
import com.raaspal.robotrecommendation.casereport.adapters.monday.MondayBoardReader;
import com.raaspal.robotrecommendation.casereport.adapters.monday.dto.MondayColumnValue;
import com.raaspal.robotrecommendation.casereport.adapters.monday.dto.MondayItem;
import com.raaspal.robotrecommendation.casereport.dto.CaseReportRow;
import com.raaspal.robotrecommendation.casereport.service.CleaningPendingReportGenerator;
import com.raaspal.robotrecommendation.casereport.service.CleaningPendingReportGenerator.Scope;
import com.raaspal.robotrecommendation.casereport.service.SlaCalculator;
import com.raaspal.robotrecommendation.casereport.service.SlaStatus;
import com.raaspal.robotrecommendation.casereport.service.SolutionLineWriter;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * How the cleaning board is split into the Cleaning, Makro and On Hold sheets.
 *
 * <p>The tickets are shaped like the live board on 2026-09-13: the Project tag blank on
 * half of them, "Makro" spelled three ways, the airports tagged on one ticket and not the
 * next, and both spellings of ท่าอากาศยาน.
 */
class CleaningPendingReportGeneratorTest {

    private static final LocalDate AS_OF = LocalDate.of(2026, 9, 13);

    private final MondayBoardReader boardReader = mock(MondayBoardReader.class);
    private CleaningPendingReportGenerator generator;

    @BeforeEach
    void board() {
        CaseSolutionAiService ai = mock(CaseSolutionAiService.class);
        when(ai.summariseProgress(any())).thenReturn("");
        generator = new CleaningPendingReportGenerator(
                boardReader, new SlaCalculator(List.of("Bangkok")), new SolutionLineWriter(ai));

        when(boardReader.readGroupItems(any(), any(), any())).thenReturn(List.of(
                // project tag | branch name | robot | open | RE | status | sup status
                ticket("1", "OneBangkok", "One Bangkok", "SpiderBot", "2026-07-16", "2026-07-16", "Pending", "Modify เพิ่ม"),
                ticket("2", null, "โรงพยาบาลเซนต์หลุยส์", "Scooter", "2026-08-29", null, "On Hold", "รอลูกค้าพิจารณาใบเสนอราคา"),
                ticket("3", "Makro", "Makro ทุ่งสง", "Omnie", "2026-09-01", null, "On Hold", "รอลูกค้าพิจารณาใบเสนอราคา"),
                ticket("4", null, "Makro สาขา : หนองคาย", "Omnie", "2026-06-28", null, "Pending", "Done"),
                ticket("5", "MaKro", "MaKro สาขา:  ขอนแก่น 3", "Omnie", "2026-09-02", "2026-09-03", "Pending", "Done"),
                ticket("6", "Makroหาดใหญ่", "Makro หาดใหญ่", "Omnie", "2026-09-12", "2026-09-12", "New", "Done"),
                ticket("7", "AOTGA-:-DMK", "AOTGA ดอนเมือง", "M75", "2026-08-06", null, "Pending", "รออะไหล่จาก GS"),
                ticket("8", null, "ท่าอากาศยานสุวรรณภูมิ", "M75", "2026-08-11", null, "Pending", "รออะไหล่จาก GS"),
                ticket("9", "ท่าอาศยานสุวรรณภูมิBKK", "ท่าอาศยานสุวรรณภูมิ BKK", "M75", "2026-09-01", null, "Pending", "รออะไหล่จาก GS"),
                ticket("10", null, "สนามบินดอนเมือง", "M50", "2026-09-07", null, "Pending", "รอลุกค้ามารับอะไหล่"),
                ticket("11", null, "MK CK5", "I-Scrub", "2026-09-10", "2026-09-10", "Pending", "Done"),
                ticket("12", "Delta", "เดลต้าอิเล็กทรอนิกส์", "M40", "2026-09-14", null, "New", "ปรับแผนงาน")));
    }

    /** Makro's tickets, whether the tag says so or only the branch name does — minus the held one. */
    @Test
    void theMakroSheetIsMakrosTicketsHoweverTheyAreSpelled() {
        List<CaseReportRow> rows = generator.generate(Scope.MAKRO, AS_OF);

        assertThat(rows).extracting(CaseReportRow::sourceItemId).containsExactly("4", "5", "6");
        assertThat(rows).extracting(CaseReportRow::project).containsOnly("Makro");
        assertThat(rows).extracting(CaseReportRow::branch)
                .containsExactly("Makro สาขา : หนองคาย", "MaKro สาขา:  ขอนแก่น 3", "Makro หาดใหญ่");
    }

    /** Everything else, minus the airports, which have their own sheet, and minus the held. */
    @Test
    void theCleaningSheetIsTheRestWithoutTheAirportsOrTheHeld() {
        List<CaseReportRow> rows = generator.generate(Scope.CLEANING, AS_OF);

        assertThat(rows).extracting(CaseReportRow::sourceItemId).containsExactly("1", "11");
        // The customer is printed under Project, from the branch name, and Branch is empty.
        assertThat(rows).extracting(CaseReportRow::project)
                .containsExactly("One Bangkok", "MK CK5");
        assertThat(rows).extracting(CaseReportRow::branch).containsOnlyNulls();
    }

    /**
     * The held cases, Makro's included, the airports' not: an airport case is on RAW_AOTGA
     * whatever its status. Both site columns are filled, since two boards share the sheet.
     */
    @Test
    void theOnHoldSheetIsEveryHeldCaseExceptTheAirports() {
        List<CaseReportRow> rows = generator.generate(Scope.ON_HOLD, AS_OF);

        assertThat(rows).extracting(CaseReportRow::sourceItemId).containsExactly("2", "3");
        assertThat(rows).extracting(CaseReportRow::sla).containsOnly(SlaStatus.ON_HOLD);
        assertThat(rows).extracting(CaseReportRow::project)
                .containsExactly("โรงพยาบาลเซนต์หลุยส์", "Makro");
        assertThat(rows).extracting(CaseReportRow::branch)
                .containsExactly("โรงพยาบาลเซนต์หลุยส์", "Makro ทุ่งสง");
        // The generator does not know which board it is; the combiner stamps that.
        assertThat(rows).extracting(CaseReportRow::board).containsOnlyNulls();
    }

    /** No sheet loses a ticket to another, and no ticket is on two. */
    @Test
    void theThreeSheetsDoNotOverlap() {
        List<String> cleaning = generator.generate(Scope.CLEANING, AS_OF).stream()
                .map(CaseReportRow::sourceItemId).toList();
        List<String> makro = generator.generate(Scope.MAKRO, AS_OF).stream()
                .map(CaseReportRow::sourceItemId).toList();
        List<String> onHold = generator.generate(Scope.ON_HOLD, AS_OF).stream()
                .map(CaseReportRow::sourceItemId).toList();

        assertThat(cleaning).doesNotContainAnyElementsOf(makro).doesNotContainAnyElementsOf(onHold);
        assertThat(makro).doesNotContainAnyElementsOf(onHold);
        // Every non-airport ticket open on the date is on exactly one of them.
        assertThat(List.of(cleaning, makro, onHold).stream().flatMap(List::stream).sorted().toList())
                .containsExactly("1", "11", "2", "3", "4", "5", "6");
    }

    /** 3 days everywhere, no province; exclusive count. */
    @Test
    void slaIsThreeDaysEverywhere() {
        Map<String, CaseReportRow> byId = new java.util.HashMap<>();
        generator.generate(Scope.CLEANING, AS_OF).forEach(r -> byId.put(r.sourceItemId(), r));

        CaseReportRow oneBangkok = byId.get("1");     // opened 16 Jul, Pending
        assertThat(oneBangkok.days()).isEqualTo(59);
        assertThat(oneBangkok.sla()).isEqualTo(SlaStatus.BREACHED);
        assertThat(oneBangkok.province()).isNull();

        CaseReportRow mkCk5 = byId.get("11");         // opened 10 Sep, 3 days: at the limit
        assertThat(mkCk5.days()).isEqualTo(3);
        assertThat(mkCk5.sla()).isEqualTo(SlaStatus.WITHIN);
    }

    /** A ticket opened after the report date is not on that date's sheet. */
    @Test
    void aTicketOpenedAfterTheReportDateIsLeftOut() {
        assertThat(generator.generate(Scope.CLEANING, AS_OF))
                .extracting(CaseReportRow::sourceItemId).doesNotContain("12");
        assertThat(generator.generate(Scope.CLEANING, AS_OF.plusDays(1)))
                .extracting(CaseReportRow::sourceItemId).contains("12");
    }

    private static MondayItem ticket(String id, String project, String branch, String robot,
                                     String open, String re, String status, String supStatus) {
        List<MondayColumnValue> cells = new ArrayList<>();
        cells.add(new MondayColumnValue("asset_owner3__1", "tags", project, null));
        cells.add(new MondayColumnValue("text6", "text", branch, null));
        cells.add(new MondayColumnValue("status_17", "status", robot, null));
        cells.add(new MondayColumnValue("text0", "text", "GS-" + id, null));
        cells.add(new MondayColumnValue("text", "text", "ปัญหา " + id, null));
        cells.add(new MondayColumnValue("date8", "date", open, null));
        cells.add(new MondayColumnValue("date_1", "date", re, null));
        cells.add(new MondayColumnValue("status", "status", status, null));
        cells.add(new MondayColumnValue("status7", "status", supStatus, null));
        return new MondayItem(id, branch, null, null, cells, List.of(), null);
    }
}
