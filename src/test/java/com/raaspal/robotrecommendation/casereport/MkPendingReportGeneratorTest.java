package com.raaspal.robotrecommendation.casereport;

import com.raaspal.robotrecommendation.ai.service.CaseSolutionAiService;
import com.raaspal.robotrecommendation.casereport.adapters.monday.MondayBoardReader;
import com.raaspal.robotrecommendation.casereport.adapters.monday.dto.MondayColumnValue;
import com.raaspal.robotrecommendation.casereport.adapters.monday.dto.MondayItem;
import com.raaspal.robotrecommendation.casereport.adapters.monday.dto.MondayUpdate;
import com.raaspal.robotrecommendation.casereport.dto.CaseProgressRequest;
import com.raaspal.robotrecommendation.casereport.dto.CaseReportRow;
import com.raaspal.robotrecommendation.casereport.service.MkPendingReportGenerator;
import com.raaspal.robotrecommendation.casereport.service.SlaCalculator;
import com.raaspal.robotrecommendation.casereport.service.SolutionLineWriter;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * What the generator hands the model when it writes a Solution line, and how the delivery
 * board is split into the MK, Delivery and On Hold sheets.
 */
class MkPendingReportGeneratorTest {

    /**
     * monday's timestamps are UTC. A visit logged at 06:30 on 11 September in Bangkok is
     * 23:30 on the 10th in UTC, and the report has to date it the 11th, the day the RE
     * team was actually there.
     */
    @Test
    void aCommentIsDatedByTheDayItWasPostedInBangkok() {
        MondayBoardReader boardReader = mock(MondayBoardReader.class);
        CaseSolutionAiService solutionAi = mock(CaseSolutionAiService.class);
        when(solutionAi.summariseProgress(any())).thenReturn("11-Sep เจ้าหน้าที่เข้าซ่อม");

        MondayItem ticket = new MondayItem(
                "1", "M154", null, null,
                List.of(new MondayColumnValue("asset_owner", "status", "#MK", null, null),
                        new MondayColumnValue("date5", "date", "2026-09-08", null, null)),
                List.of(new MondayUpdate("u1", "เจ้าหน้าที่เข้าซ่อมหน้างาน",
                        OffsetDateTime.parse("2026-09-10T23:30:00Z"), null, null)),
                null);
        when(boardReader.readGroupItems(any(), any(), any())).thenReturn(List.of(ticket));

        new MkPendingReportGenerator(boardReader, new SlaCalculator(List.of("Bangkok")),
                new SolutionLineWriter(solutionAi))
                .generate(MkPendingReportGenerator.Scope.MK, LocalDate.of(2026, 9, 11));

        ArgumentCaptor<CaseProgressRequest> sent = ArgumentCaptor.forClass(CaseProgressRequest.class);
        verify(solutionAi).summariseProgress(sent.capture());
        assertThat(sent.getValue().comments())
                .extracting(CaseProgressRequest.Comment::postedOn)
                .containsExactly(LocalDate.of(2026, 9, 11));
    }

    /**
     * MK keeps its held case by request; Delivery gives its up to On Hold, which takes the
     * held cases of every customer. A ticket with no Project tag is nobody's, so it lands on
     * Delivery rather than on no sheet at all.
     */
    @Test
    void theThreeScopesSplitTheBoard() {
        MondayBoardReader boardReader = mock(MondayBoardReader.class);
        CaseSolutionAiService solutionAi = mock(CaseSolutionAiService.class);
        when(solutionAi.summariseProgress(any())).thenReturn("");
        when(boardReader.readGroupItems(any(), any(), any())).thenReturn(List.of(
                ticket("1", "#MK", "Pending"),
                ticket("2", "#Yayoi", "On Hold"),
                ticket("3", "#BBQ Plaza", "Pending"),
                ticket("4", "#BBQ Plaza", "On Hold"),
                ticket("5", null, "Pending")));
        MkPendingReportGenerator generator = new MkPendingReportGenerator(
                boardReader, new SlaCalculator(List.of("Bangkok")), new SolutionLineWriter(solutionAi));
        LocalDate asOf = LocalDate.of(2026, 9, 16);

        assertThat(generator.generate(MkPendingReportGenerator.Scope.MK, asOf))
                .extracting(CaseReportRow::sourceItemId).containsExactly("1", "2");
        assertThat(generator.generate(MkPendingReportGenerator.Scope.OTHER, asOf))
                .extracting(CaseReportRow::sourceItemId).containsExactly("3", "5");
        assertThat(generator.generate(MkPendingReportGenerator.Scope.ON_HOLD, asOf))
                .extracting(CaseReportRow::sourceItemId).containsExactly("2", "4");
    }

    /** Each held row says whose hold it is, read from the column that says On Hold. */
    @Test
    void aHeldRowSaysWhoseHoldItIs() {
        MondayBoardReader boardReader = mock(MondayBoardReader.class);
        CaseSolutionAiService solutionAi = mock(CaseSolutionAiService.class);
        when(solutionAi.summariseProgress(any())).thenReturn("");
        when(boardReader.readGroupItems(any(), any(), any())).thenReturn(List.of(
                ticket("1", "#MK", "On Hold", null),
                ticket("2", "#MK", "Pending", "On Hold"),
                ticket("3", "#MK", "Pending", null)));

        List<CaseReportRow> rows = new MkPendingReportGenerator(
                boardReader, new SlaCalculator(List.of("Bangkok")), new SolutionLineWriter(solutionAi))
                .generate(MkPendingReportGenerator.Scope.MK, LocalDate.of(2026, 9, 16));

        assertThat(rows).extracting(CaseReportRow::heldBy)
                .containsExactly(CaseReportRow.HELD_BY_CUSTOMER, CaseReportRow.HELD_BY_RAASPAL, null);
    }

    /** A ticket nobody has commented on gets the RE team's opener, dated the day it opened. */
    @Test
    void aSilentTicketGetsTheOpenerDatedTheOpenDate() {
        MondayBoardReader boardReader = mock(MondayBoardReader.class);
        CaseSolutionAiService solutionAi = mock(CaseSolutionAiService.class);
        when(boardReader.readGroupItems(any(), any(), any())).thenReturn(List.of(ticket("1", "#Yayoi", "New")));

        List<CaseReportRow> rows = new MkPendingReportGenerator(
                boardReader, new SlaCalculator(List.of("Bangkok")), new SolutionLineWriter(solutionAi))
                .generate(MkPendingReportGenerator.Scope.MK, LocalDate.of(2026, 9, 16));

        assertThat(rows).singleElement().extracting(CaseReportRow::solution)
                .isEqualTo("10-Sep " + SolutionLineWriter.OPENER);
        verify(solutionAi, never()).summariseProgress(any());
    }

    private static MondayItem ticket(String id, String project, String status) {
        return ticket(id, project, status, null);
    }

    private static MondayItem ticket(String id, String project, String status, String supStatus) {
        return new MondayItem(id, "ticket " + id, null, null,
                // Five components since this branch: main's fourth is the raw
                // value, and the column ref it carries is the fifth.
                List.of(new MondayColumnValue("asset_owner", "status", project, null, null),
                        new MondayColumnValue("date5", "date", "2026-09-10", null, null),
                        new MondayColumnValue("status", "status", status, null, null),
                        new MondayColumnValue("status_1", "status", supStatus, null, null)),
                List.of(), null);
    }
}
