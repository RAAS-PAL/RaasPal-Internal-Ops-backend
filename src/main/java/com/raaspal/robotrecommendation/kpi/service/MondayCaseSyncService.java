package com.raaspal.robotrecommendation.kpi.service;

import com.raaspal.robotrecommendation.casereport.adapters.monday.MondayApiClient;
import com.raaspal.robotrecommendation.casereport.adapters.monday.MondayBoardReader;
import com.raaspal.robotrecommendation.casereport.adapters.monday.dto.MondayGroup;
import com.raaspal.robotrecommendation.casereport.adapters.monday.dto.MondayGroupRead;
import com.raaspal.robotrecommendation.casereport.adapters.monday.dto.MondayItem;
import com.raaspal.robotrecommendation.common.exception.BadRequestException;
import com.raaspal.robotrecommendation.kpi.config.KpiMondayProperties;
import com.raaspal.robotrecommendation.kpi.entity.CaseTicketSyncRun;
import com.raaspal.robotrecommendation.kpi.entity.ServiceLine;
import com.raaspal.robotrecommendation.kpi.entity.TicketType;
import com.raaspal.robotrecommendation.kpi.repository.CaseTicketSyncRunRepository;
import jakarta.annotation.PreDestroy;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Pulls every configured monday board into {@code case_ticket}. One run reads
 * each board's groups in full and hands the rows to {@link CaseTicketWriter},
 * recording a {@link CaseTicketSyncRun} per board either way.
 *
 * <p>Two entry points: {@link #syncAll} runs inline (the scheduler), and
 * {@link #start} runs on a background thread and returns at once (the console's
 * button — a full read of both boards takes longer than a request should). Both
 * share one lock, so a scheduled run and a manual run cannot overlap and read
 * the same board twice inside monday's daily call budget.
 *
 * <p>A board that fails does not stop the others: Cleaning still syncs when
 * Delivery's group ids are wrong, and the failed board's run row says why.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class MondayCaseSyncService {

    private final KpiMondayProperties properties;
    private final MondayApiClient apiClient;
    private final MondayBoardReader boardReader;
    private final CaseTicketWriter writer;
    private final CaseTicketSyncRunRepository runRepository;

    private final ExecutorService executor = Executors.newSingleThreadExecutor(runnable -> {
        Thread thread = new Thread(runnable, "kpi-monday-sync");
        thread.setDaemon(true);
        return thread;
    });
    private final AtomicBoolean running = new AtomicBoolean(false);
    private volatile SyncSummary lastSummary;

    /** Outcome for one board. */
    public record BoardResult(
            String boardId,
            ServiceLine serviceLine,
            TicketType ticketType,
            CaseTicketSyncRun.Status status,
            int groupsRead,
            int itemsRead,
            int inserted,
            int updated,
            int unchanged,
            int markedAbsent,
            String error) {
    }

    /** Outcome of one whole run. */
    public record SyncSummary(
            CaseTicketSyncRun.Trigger trigger,
            LocalDateTime startedAt,
            LocalDateTime finishedAt,
            List<BoardResult> boards) {

        public boolean allSucceeded() {
            return boards.stream().allMatch(b -> b.status() == CaseTicketSyncRun.Status.SUCCEEDED);
        }
    }

    /** Whether a run is in progress, whether one could be, and the last finished one. */
    public record SyncStatus(boolean running, boolean configured, SyncSummary lastSummary) {
    }

    public SyncStatus status() {
        return new SyncStatus(running.get(), isConfigured(), lastSummary);
    }

    /** A token and at least one board — the two things a run cannot do without. */
    public boolean isConfigured() {
        return apiClient.isConfigured() && !properties.getBoards().isEmpty();
    }

    /**
     * Runs every board now, on the calling thread.
     *
     * @throws BadRequestException   when monday is not configured
     * @throws IllegalStateException when a run is already in progress (surfaces as 409)
     */
    public SyncSummary syncAll(CaseTicketSyncRun.Trigger trigger) {
        requireConfigured();
        acquire();
        return runLocked(trigger);
    }

    /**
     * Starts a run in the background and returns immediately; poll
     * {@link #status()} for the result. The lock is taken here, not on the worker
     * thread, so the returned status already says {@code running}.
     */
    public SyncStatus start(CaseTicketSyncRun.Trigger trigger) {
        requireConfigured();
        acquire();
        executor.submit(() -> {
            try {
                runLocked(trigger);
            } catch (Exception e) {
                // runLocked catches per-board failures; this is for anything else.
                log.error("Background monday case sync failed: {}", e.getMessage(), e);
            }
        });
        return status();
    }

    private void requireConfigured() {
        if (!apiClient.isConfigured()) {
            throw new BadRequestException("monday API token is not configured (MONDAY_API_TOKEN)");
        }
        if (properties.getBoards().isEmpty()) {
            throw new BadRequestException("No monday boards are configured (app.kpi.monday.boards)");
        }
    }

    private void acquire() {
        if (!running.compareAndSet(false, true)) {
            throw new IllegalStateException("A monday case sync is already running");
        }
    }

    /** Must only be called with the lock held; releases it. */
    private SyncSummary runLocked(CaseTicketSyncRun.Trigger trigger) {
        LocalDateTime startedAt = LocalDateTime.now();
        List<BoardResult> results = new ArrayList<>();
        try {
            for (KpiMondayProperties.Board board : properties.getBoards()) {
                results.add(syncBoard(board, trigger));
            }
        } finally {
            SyncSummary summary = new SyncSummary(trigger, startedAt, LocalDateTime.now(), List.copyOf(results));
            lastSummary = summary;
            running.set(false);
            log.info("monday case sync ({}) finished: {}", trigger,
                    summary.allSucceeded() ? "all boards succeeded" : "at least one board failed");
        }
        return lastSummary;
    }

    private BoardResult syncBoard(KpiMondayProperties.Board board, CaseTicketSyncRun.Trigger trigger) {
        CaseTicketSyncRun run = runRepository.save(CaseTicketSyncRun.builder()
                .sourceBoardId(board.getId())
                .serviceLine(board.getServiceLine())
                .ticketType(board.getTicketType())
                .status(CaseTicketSyncRun.Status.RUNNING)
                .triggeredBy(trigger)
                .startedAt(LocalDateTime.now())
                .build());
        try {
            List<String> groupIds = board.getGroupIds().isEmpty() ? discoverGroups(board.getId()) : board.getGroupIds();

            List<MondayItem> items = new ArrayList<>();
            boolean complete = true;
            for (String groupId : groupIds) {
                MondayGroupRead read = boardReader.readGroup(board.getId(), groupId, List.of(), false);
                items.addAll(read.items());
                complete &= read.complete();
            }
            if (!complete) {
                log.warn("Board {} was not read completely; rows missing from this read will NOT be marked absent",
                        board.getId());
            }

            CaseTicketWriter.WriteResult written = writer.upsert(board, items, complete, LocalDateTime.now());

            run.setStatus(CaseTicketSyncRun.Status.SUCCEEDED);
            run.setGroupsRead(groupIds.size());
            run.setItemsRead(items.size());
            run.setItemsInserted(written.inserted());
            run.setItemsUpdated(written.updated());
            run.setItemsUnchanged(written.unchanged());
            run.setItemsMarkedAbsent(written.markedAbsent());
            run.setFinishedAt(LocalDateTime.now());
            runRepository.save(run);

            return new BoardResult(board.getId(), board.getServiceLine(), board.getTicketType(), run.getStatus(),
                    groupIds.size(), items.size(), written.inserted(), written.updated(), written.unchanged(),
                    written.markedAbsent(), null);
        } catch (Exception e) {
            log.error("monday case sync failed for board {} ({}): {}", board.getId(), board.getServiceLine(),
                    e.getMessage(), e);
            run.setStatus(CaseTicketSyncRun.Status.FAILED);
            run.setErrorMessage(e.getMessage());
            run.setFinishedAt(LocalDateTime.now());
            runRepository.save(run);
            return new BoardResult(board.getId(), board.getServiceLine(), board.getTicketType(), run.getStatus(),
                    0, 0, 0, 0, 0, 0, e.getMessage());
        }
    }

    /** Every group on the board — closed tickets get moved between groups, and the KPI needs them all. */
    private List<String> discoverGroups(String boardId) {
        List<MondayGroup> groups = boardReader.describeBoard(boardId).groups();
        List<String> ids = new ArrayList<>();
        if (groups != null) {
            groups.forEach(group -> ids.add(group.id()));
        }
        return ids;
    }

    @PreDestroy
    void shutdown() {
        executor.shutdownNow();
    }
}
