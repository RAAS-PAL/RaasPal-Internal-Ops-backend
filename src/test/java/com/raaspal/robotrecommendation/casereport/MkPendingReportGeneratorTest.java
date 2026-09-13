package com.raaspal.robotrecommendation.casereport;

import com.raaspal.robotrecommendation.ai.service.CaseSolutionAiService;
import com.raaspal.robotrecommendation.casereport.adapters.monday.MondayBoardReader;
import com.raaspal.robotrecommendation.casereport.adapters.monday.dto.MondayColumnValue;
import com.raaspal.robotrecommendation.casereport.adapters.monday.dto.MondayItem;
import com.raaspal.robotrecommendation.casereport.adapters.monday.dto.MondayUpdate;
import com.raaspal.robotrecommendation.casereport.dto.CaseProgressRequest;
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
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * What the generator hands the model when it writes a Solution line.
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
                List.of(new MondayColumnValue("asset_owner", "status", "#MK", null),
                        new MondayColumnValue("date5", "date", "2026-09-08", null)),
                List.of(new MondayUpdate("u1", "เจ้าหน้าที่เข้าซ่อมหน้างาน",
                        OffsetDateTime.parse("2026-09-10T23:30:00Z"), null)),
                null);
        when(boardReader.readGroupItems(any(), any(), any())).thenReturn(List.of(ticket));

        new MkPendingReportGenerator(boardReader, new SlaCalculator(List.of("Bangkok")),
                new SolutionLineWriter(solutionAi))
                .generate(LocalDate.of(2026, 9, 11));

        ArgumentCaptor<CaseProgressRequest> sent = ArgumentCaptor.forClass(CaseProgressRequest.class);
        verify(solutionAi).summariseProgress(sent.capture());
        assertThat(sent.getValue().comments())
                .extracting(CaseProgressRequest.Comment::postedOn)
                .containsExactly(LocalDate.of(2026, 9, 11));
    }
}
