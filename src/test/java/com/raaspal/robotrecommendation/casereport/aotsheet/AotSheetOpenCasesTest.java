package com.raaspal.robotrecommendation.casereport.aotsheet;

import com.raaspal.robotrecommendation.casereport.adapters.googlesheet.GoogleSheetApiClient;
import com.raaspal.robotrecommendation.casereport.entity.CaseSource;
import com.raaspal.robotrecommendation.casereport.entity.CaseTicket;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** A stored sheet row as the AOT tab lists it, and unlinking the sheet. */
class AotSheetOpenCasesTest {

    private static final LocalDate TODAY = LocalDate.of(2026, 9, 28);

    private static CaseTicket ticket(LocalDate openDate) {
        return CaseTicket.builder()
                .source(CaseSource.GOOGLE_SHEET)
                .sourceBoardId("sheet-id")
                .sourceItemId("sheet-id:AOT-0012")
                .itemName("T-77")
                .branchRaw("Terminal 1")
                .robotModel("M75")
                .serialNumbers("SN-1")
                .mainIssue("Brush motor noise")
                .solution("Verified on site")
                .status("Open")
                .openDate(openDate)
                .build();
    }

    @Test
    void theRowIdLosesItsSpreadsheetPrefixAndDaysCountLikeThePendingSheets() {
        AotSheetProperties props = new AotSheetProperties();
        props.setRequestedPartHeader("Request for Spare part");
        props.setRepairByHeader("repair By");

        AotSheetOpenCases.Item item = AotSheetSyncService.openCase(ticket(LocalDate.of(2026, 9, 25)),
                Map.of("Request for Spare part", " Brush motor ", "repair By", ""), props, TODAY);

        assertThat(item.rowId()).isEqualTo("AOT-0012");
        assertThat(item.ticketNo()).isEqualTo("T-77");
        // Opened on the 25th, read on the 28th: 3, not counting the open day.
        assertThat(item.days()).isEqualTo(3);
        assertThat(item.requestedPart()).isEqualTo("Brush motor");
        assertThat(item.repairBy()).isNull();
        assertThat(item.verifyNote()).isEqualTo("Verified on site");
    }

    @Test
    void noOpenDateOrOneInTheFutureHasNoDays() {
        AotSheetProperties props = new AotSheetProperties();
        assertThat(AotSheetSyncService.openCase(ticket(null), Map.of(), props, TODAY).days()).isNull();
        assertThat(AotSheetSyncService.openCase(ticket(TODAY.plusDays(2)), Map.of(), props, TODAY).days()).isNull();
    }

    @Test
    void removingTheLinkDeletesTheSavedRow() {
        CaseSourceSheetRepository repository = mock(CaseSourceSheetRepository.class);
        CaseSourceSheet saved = CaseSourceSheet.builder().sourceKey(CaseSourceSheet.AOT).sheetUrl("x")
                .spreadsheetId("sheet-id").tab("Case").headerRow(1).build();
        when(repository.findBySourceKey(CaseSourceSheet.AOT)).thenReturn(Optional.of(saved), Optional.empty());
        GoogleSheetApiClient client = mock(GoogleSheetApiClient.class);

        AotSheetSettingsService.SettingsView after =
                new AotSheetSettingsService(new AotSheetProperties(), repository, client).remove();

        verify(repository).delete(saved);
        assertThat(after.savedInConsole()).isFalse();
        assertThat(after.spreadsheetId()).isNull();
        assertThat(after.syncEnabled()).isFalse();
    }
}
