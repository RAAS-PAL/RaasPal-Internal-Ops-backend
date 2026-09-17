package com.raaspal.robotrecommendation.casereport.brand;

import com.raaspal.robotrecommendation.casereport.adapters.monday.MondayBoardReader;
import com.raaspal.robotrecommendation.casereport.adapters.monday.MondayBoardReader.FilterRule;
import com.raaspal.robotrecommendation.casereport.service.CaseTicketSyncService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Pulls one brand's tickets - every group, open and done - into {@code case_ticket}.
 *
 * <p>The daily open-group sync only ever sees the "All Case" group, which is right for
 * the pending reports and useless for a brand review: three quarters of AutoXing's
 * tickets are in the Done groups. This asks monday for just that brand's rows with a
 * server-side filter, so the whole history costs one API call rather than a walk of the
 * board.
 *
 * <p>Runs after the board snapshots in {@code CaseSyncCoordinator}, and on demand from
 * the console's Refresh button. The last outcome is kept in memory for the status
 * endpoint; the durable record is the tickets' own {@code last_synced_at}.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class BrandTicketSyncService {

    private final BrandTicketProperties properties;
    private final MondayBoardReader boardReader;
    private final CaseTicketSyncService syncService;

    /** What the last run for a brand did, or why it failed. */
    public record LastRun(LocalDateTime startedAt, LocalDateTime finishedAt,
                          CaseTicketSyncService.SyncResult result, String error) {
        public boolean ok() {
            return error == null;
        }
    }

    private final Map<String, LastRun> lastRuns = new ConcurrentHashMap<>();

    public LastRun lastRun(String brandKey) {
        return lastRuns.get(brandKey);
    }

    /** Every configured brand, each on its own; one failing does not stop the next. */
    public List<LastRun> syncAll() {
        List<LastRun> runs = new ArrayList<>();
        for (BrandTicketProperties.Brand brand : properties.getBrands()) {
            runs.add(sync(brand));
        }
        return runs;
    }

    public LastRun sync(BrandTicketProperties.Brand brand) {
        LocalDateTime started = LocalDateTime.now();
        LastRun run;
        try {
            CaseTicketSyncService.SyncResult result = syncService.syncFiltered(
                    brand.getBoardId(),
                    brand.getOpenGroupId(),
                    rulesFor(brand),
                    CaseTicketSyncService.DELIVERY_COLUMNS,
                    "status", "status_1", "date5");
            run = new LastRun(started, LocalDateTime.now(), result, null);
            log.info("Brand sync {}: {} tickets, {} new, {} new comments",
                    brand.getKey(), result.seen(), result.created(), result.newComments());
        } catch (Exception e) {
            run = new LastRun(started, LocalDateTime.now(), null, e.getMessage());
            log.error("Brand sync {} failed", brand.getKey(), e);
        }
        lastRuns.put(brand.getKey(), run);
        return run;
    }

    /**
     * The monday filter: model label is any of the brand's, OR the name contains any of
     * its terms. Label indexes are read live because a rule on a status column takes
     * indexes, not text.
     */
    private List<FilterRule> rulesFor(BrandTicketProperties.Brand brand) {
        List<FilterRule> rules = new ArrayList<>();
        if (!brand.getModels().isEmpty()) {
            List<Integer> indexes = boardReader.statusLabelIndexes(
                    brand.getBoardId(), brand.getModelColumn(), brand.getModels());
            if (!indexes.isEmpty()) {
                rules.add(FilterRule.anyOf(brand.getModelColumn(), indexes));
            }
        }
        for (String term : brand.getNameTerms()) {
            rules.add(FilterRule.containsText("name", term));
        }
        if (rules.isEmpty()) {
            throw new IllegalStateException("Brand " + brand.getKey() + " has no models or name terms configured");
        }
        return rules;
    }
}
