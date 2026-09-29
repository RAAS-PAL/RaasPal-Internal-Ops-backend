package com.raaspal.robotrecommendation.casereport.aotsheet;

import com.raaspal.robotrecommendation.casereport.adapters.googlesheet.GoogleSheetException;
import com.raaspal.robotrecommendation.casereport.service.CaseTicketSyncService.SyncResult;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.time.LocalDateTime;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Keeps the AOT sheet current without anyone pressing Sync now: the 15-minute pending-case
 * refresh calls it, and so does the AOT tab when it is opened.
 *
 * <p>Skips rather than fails whenever a sync would be pointless or unsafe - switched off,
 * synced moments ago, or already running - so an open tab can call it freely. Two callers
 * at once cannot both sync: the second is told one is running.
 *
 * <p>A separate bean from {@link AotSheetSyncService} so the call goes through that
 * service's proxy and {@code sync()} keeps its transaction.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class AotSheetAutoSync {

    private final AotSheetSyncService service;
    private final AtomicBoolean running = new AtomicBoolean(false);

    /**
     * @param synced     whether a sync ran this time
     * @param reason     why not, when it did not; or what went wrong
     * @param failed     the sync was tried and failed (Google or the sheet refused)
     * @param lastSynced when the sheet was last synced, after this call
     */
    public record Outcome(boolean synced, boolean failed, String reason, SyncResult result, LocalDateTime lastSynced) {
    }

    /** Syncs unless the last sync is younger than {@code minAge}. */
    public Outcome syncIfDue(Duration minAge) {
        if (!service.isEnabled()) {
            return skipped("The sheet's sync is off.");
        }
        LocalDateTime last = service.lastSyncedAt();
        if (last != null && last.isAfter(LocalDateTime.now().minus(minAge))) {
            return skipped("Synced less than " + minAge.toMinutes() + " minutes ago.");
        }
        if (!running.compareAndSet(false, true)) {
            return skipped("A sync is already running.");
        }
        try {
            SyncResult result = service.sync();
            return new Outcome(true, false, null, result, service.lastSyncedAt());
        } catch (GoogleSheetException | IllegalStateException e) {
            // Not configured after all, the sheet unshared or renamed, an empty read: the
            // sync refuses on purpose. Said once to the caller; the stored copy stands.
            log.warn("AOT sheet auto-sync did not run: {}", e.getMessage());
            return new Outcome(false, true, e.getMessage(), null, service.lastSyncedAt());
        } finally {
            running.set(false);
        }
    }

    private Outcome skipped(String reason) {
        return new Outcome(false, false, reason, null, service.lastSyncedAt());
    }
}
