package com.raaspal.robotrecommendation.casereport.service;

import com.raaspal.robotrecommendation.casereport.brand.BrandTicketSyncService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;

/**
 * Runs the daily sync across every board.
 *
 * <p>A separate bean from {@link CaseTicketSyncService} for a reason that is easy to get
 * wrong: {@code @Transactional} is applied by a proxy, and a method calling another method
 * on the same instance goes straight to the implementation, proxy bypassed. Looping over
 * the boards inside the sync service therefore ran the whole thing with no transaction at
 * all, and failed on the first flush with "No EntityManager with actual transaction
 * available for current thread". Calling across beans is what makes the annotation real.
 *
 * <p>It also gives the boundary the right shape. Each board is its own transaction, so a
 * failure reading cleaning leaves the delivery snapshot committed — losing one board's day
 * is bad, losing both because of one is worse.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class CaseSyncCoordinator {

    private final CaseTicketSyncService syncService;
    private final BrandTicketSyncService brandSync;

    /**
     * Every board, each in its own transaction.
     *
     * <p>A board that throws is logged and skipped rather than aborting the run. The
     * snapshot for a given day cannot be taken later — monday cannot say what a ticket
     * looked like yesterday — so salvaging the boards that did work is strictly better
     * than losing the day because one of them failed.
     */
    public List<CaseTicketSyncService.SyncResult> syncAll() {
        List<CaseTicketSyncService.SyncResult> results = new ArrayList<>();

        for (CaseTicketSyncService.BoardSpec spec : CaseTicketSyncService.BOARDS) {
            try {
                results.add(syncService.sync(spec));
            } catch (Exception e) {
                log.error("Sync failed for board {} — that board has no snapshot for today, "
                        + "and it cannot be backfilled later.", spec.boardId(), e);
            }
        }

        return results;
    }
}
