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

    /**
     * The tracker reads the rows right after: it waits for a sync already running instead of
     * reading the rows from before it, and does not sync again once that one is done.
     */
    @Test
    void aReaderWaitsForTheRunningSyncInsteadOfReadingAroundIt() throws Exception {
        java.util.concurrent.CountDownLatch inSync = new java.util.concurrent.CountDownLatch(1);
        java.util.concurrent.CountDownLatch release = new java.util.concurrent.CountDownLatch(1);
        when(service.isEnabled()).thenReturn(true);
        when(service.lastSyncedAt()).thenReturn(null);
        when(service.sync()).thenAnswer(invocation -> {
            inSync.countDown();
            release.await();
            when(service.lastSyncedAt()).thenReturn(LocalDateTime.now());
            when(service.syncedSinceStart()).thenReturn(true);
            return new SyncResult("sheet", 12, 0, 12, 0, 0, 0);
        });
        Thread opening = new Thread(() -> autoSync.syncIfDue(Duration.ofMinutes(1)));
        opening.start();
        inSync.await();

        var reading = java.util.concurrent.CompletableFuture.supplyAsync(() -> autoSync.syncFirstIfDue(Duration.ofMinutes(1)));
        Thread.sleep(100);
        assertThat(reading).isNotDone();
        release.countDown();
        opening.join();

        assertThat(reading.get().synced()).isFalse();
        verify(service, org.mockito.Mockito.times(1)).sync();
    }

    /** Synced a moment ago by the process before a restart: the rows kept in memory are not here yet. */
    @Test
    void aReaderSyncsFirstWhenThisProcessHasNotSyncedYet() {
        when(service.isEnabled()).thenReturn(true);
        when(service.lastSyncedAt()).thenReturn(LocalDateTime.now().minusSeconds(20));
        when(service.syncedSinceStart()).thenReturn(false);
        when(service.sync()).thenReturn(new SyncResult("sheet", 12, 0, 12, 0, 0, 0));

        assertThat(autoSync.syncFirstIfDue(Duration.ofMinutes(1)).synced()).isTrue();
        assertThat(autoSync.syncIfDue(Duration.ofMinutes(1)).synced()).isFalse();
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
