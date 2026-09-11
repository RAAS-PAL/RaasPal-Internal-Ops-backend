package com.raaspal.robotrecommendation.pm.service;

import com.raaspal.robotrecommendation.casereport.adapters.monday.MondayApiClient;
import com.raaspal.robotrecommendation.pm.config.PmMondayProperties;
import com.raaspal.robotrecommendation.pm.entity.PmSyncRun;
import com.raaspal.robotrecommendation.pm.repository.PmSyncRunRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;

import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Pulls both monday PM boards into the local mirror.
 *
 * <p>Orchestration only - the per-board writing lives in {@link PmBoardSyncer} so
 * it gets a real transaction, and the run bookkeeping stays here, outside that
 * transaction, so a row recording a failure is not rolled back with it.
 *
 * <p>One board failing does not stop the other. They are independent boards with
 * independent column mappings, and half a planner is more useful than none.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class MondayPmSyncService {

    private final PmMondayProperties properties;
    private final PmBoardSyncer boardSyncer;
    private final MondayApiClient mondayApiClient;
    private final PmSyncRunRepository syncRunRepository;

    /** Stops a scheduled run and a hand-pressed one from overlapping. */
    private final AtomicBoolean running = new AtomicBoolean(false);

    public boolean isRunning() {
        return running.get();
    }

    /** Outcome of syncing every configured board. */
    public record SyncSummary(int boards, int contractsWritten, int visitsWritten, int failures,
                              List<String> messages) {
    }

    /**
     * Syncs every configured board, in the calling thread.
     *
     * @param triggeredBy "scheduler", or the email of whoever pressed sync
     */
    public SyncSummary syncAll(String triggeredBy) {
        if (!running.compareAndSet(false, true)) {
            return new SyncSummary(0, 0, 0, 0, List.of("A PM sync is already running"));
        }
        try {
            if (!mondayApiClient.isConfigured()) {
                return new SyncSummary(0, 0, 0, 1,
                        List.of("monday API token is not configured (app.monday.api.token)"));
            }
            if (properties.getBoards().isEmpty()) {
                return new SyncSummary(0, 0, 0, 1, List.of("No PM boards configured (app.pm.monday.boards)"));
            }

            int contracts = 0;
            int visits = 0;
            int failures = 0;
            List<String> messages = new ArrayList<>();

            for (PmMondayProperties.Board board : properties.getBoards()) {
                OffsetDateTime runStart = OffsetDateTime.now();
                PmSyncRun run = syncRunRepository.save(PmSyncRun.builder()
                        .id(UUID.randomUUID())
                        .sourceBoardId(board.getId())
                        .serviceLine(board.getServiceLine())
                        .status("RUNNING")
                        .triggeredBy(triggeredBy)
                        .startedAt(runStart)
                        .build());
                try {
                    PmBoardSyncer.BoardResult result = boardSyncer.syncBoard(board, runStart);

                    run.setStatus("SUCCESS");
                    run.setFinishedAt(OffsetDateTime.now());
                    run.setContractsRead(result.contractsRead());
                    run.setVisitsRead(result.visitsRead());
                    run.setContractsWritten(result.contractsWritten());
                    run.setVisitsWritten(result.visitsWritten());
                    run.setMarkedAbsent(result.markedAbsent());
                    syncRunRepository.save(run);

                    contracts += result.contractsWritten();
                    visits += result.visitsWritten();
                    messages.add("%s: %d contracts, %d visits%s".formatted(
                            board.getServiceLine(), result.contractsWritten(), result.visitsWritten(),
                            result.orphanSubitems() > 0
                                    ? " (%d subitems skipped, parent not on the board)"
                                            .formatted(result.orphanSubitems())
                                    : ""));
                } catch (Exception e) {
                    failures++;
                    log.error("PM sync failed for board {} ({})", board.getId(), board.getServiceLine(), e);
                    run.setStatus("FAILED");
                    run.setFinishedAt(OffsetDateTime.now());
                    run.setErrorMessage(e.getMessage());
                    syncRunRepository.save(run);
                    messages.add("%s (board %s) failed: %s"
                            .formatted(board.getServiceLine(), board.getId(), e.getMessage()));
                }
            }
            return new SyncSummary(properties.getBoards().size(), contracts, visits, failures, messages);
        } finally {
            running.set(false);
        }
    }

    /** The most recent runs, newest first. */
    public List<PmSyncRun> recentRuns(int limit) {
        return syncRunRepository.findAllByOrderByStartedAtDesc(PageRequest.of(0, limit));
    }
}
