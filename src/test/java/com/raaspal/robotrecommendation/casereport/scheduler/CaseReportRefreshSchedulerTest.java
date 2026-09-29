package com.raaspal.robotrecommendation.casereport.scheduler;

import com.raaspal.robotrecommendation.casereport.aotsheet.AotSheetAutoSync;
import com.raaspal.robotrecommendation.casereport.entity.CaseReportDefinition;
import com.raaspal.robotrecommendation.casereport.service.CaseReportRunService;
import com.raaspal.robotrecommendation.common.exception.BadRequestException;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.util.List;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * One refresh cycle: every sheet, regenerated for the day the cycle started on, and one
 * sheet's trouble never stops the others.
 */
class CaseReportRefreshSchedulerTest {

    private static final LocalDate DAY = LocalDate.of(2026, 9, 28);

    private final CaseReportRunService runService = mock(CaseReportRunService.class);
    private final AotSheetAutoSync aotSheet = mock(AotSheetAutoSync.class);

    @Test
    void everySheetIsRegeneratedForTheDayAndAFailureCostsOnlyItsOwnSheet() {
        when(runService.rowsFor(anyString(), eq(DAY), anyBoolean())).thenReturn(List.of());
        when(runService.rowsFor(CaseReportDefinition.CLEANING_PENDING, DAY, true))
                .thenThrow(new IllegalStateException("monday timed out"));
        when(runService.rowsFor(CaseReportDefinition.AOTGA_PENDING, DAY, true))
                .thenThrow(new BadRequestException("already sent"));

        new CaseReportRefreshScheduler(runService, aotSheet).refreshAll(DAY);

        for (String code : CaseReportDefinition.SHEETS) {
            verify(runService).rowsFor(code, DAY, true);
        }
        verify(aotSheet).syncIfDue(any());
    }

    /** The sheet rides along with the cycle, and its trouble is its own. */
    @Test
    void theAotSheetIsSyncedAfterTheBoardsAndItsFailureStopsNothing() {
        when(runService.rowsFor(anyString(), eq(DAY), anyBoolean())).thenReturn(List.of());
        when(aotSheet.syncIfDue(any())).thenThrow(new IllegalStateException("Google is down"));

        new CaseReportRefreshScheduler(runService, aotSheet).refreshAll(DAY);

        for (String code : CaseReportDefinition.SHEETS) {
            verify(runService).rowsFor(code, DAY, true);
        }
    }
}
