package com.raaspal.robotrecommendation.casereport.aotsheet;

import com.raaspal.robotrecommendation.casereport.adapters.googlesheet.GoogleSheetException;
import com.raaspal.robotrecommendation.casereport.service.CaseTicketSyncService.SyncResult;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.LocalDateTime;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** When opening the AOT tab or the refresh cycle syncs the sheet, and when it leaves it be. */
class AotSheetAutoSyncTest {

    private static final Duration FIVE_MINUTES = Duration.ofMinutes(5);

    private final AotSheetSyncService service = mock(AotSheetSyncService.class);
    private final AotSheetAutoSync autoSync = new AotSheetAutoSync(service);

    @Test
    void aSheetWhoseSyncIsOffIsLeftAlone() {
        when(service.isEnabled()).thenReturn(false);

        AotSheetAutoSync.Outcome outcome = autoSync.syncIfDue(FIVE_MINUTES);

        assertThat(outcome.synced()).isFalse();
        assertThat(outcome.failed()).isFalse();
        verify(service, never()).sync();
    }

    @Test
    void aSheetSyncedMomentsAgoIsNotSyncedAgain() {
        when(service.isEnabled()).thenReturn(true);
        when(service.lastSyncedAt()).thenReturn(LocalDateTime.now().minusMinutes(2));

        assertThat(autoSync.syncIfDue(FIVE_MINUTES).synced()).isFalse();
        verify(service, never()).sync();
    }

    @Test
    void aStaleOrNeverSyncedSheetIsSynced() {
        when(service.isEnabled()).thenReturn(true);
        when(service.lastSyncedAt()).thenReturn(null);
        when(service.sync()).thenReturn(new SyncResult("sheet", 12, 1, 11, 0, 0, 1));

        AotSheetAutoSync.Outcome outcome = autoSync.syncIfDue(FIVE_MINUTES);

        assertThat(outcome.synced()).isTrue();
        assertThat(outcome.result().seen()).isEqualTo(12);

        when(service.lastSyncedAt()).thenReturn(LocalDateTime.now().minusMinutes(9));
        assertThat(autoSync.syncIfDue(FIVE_MINUTES).synced()).isTrue();
    }

    /** A refusal (sheet unshared, a column renamed) is reported, not thrown at an open tab. */
    @Test
    void aRefusedSyncIsReportedNotThrown() {
        when(service.isEnabled()).thenReturn(true);
        when(service.sync()).thenThrow(new GoogleSheetException("The service account cannot open the sheet"));

        AotSheetAutoSync.Outcome outcome = autoSync.syncIfDue(FIVE_MINUTES);

        assertThat(outcome.synced()).isFalse();
        assertThat(outcome.failed()).isTrue();
        assertThat(outcome.reason()).contains("cannot open");
    }
}
