package com.raaspal.robotrecommendation.casereport.brand;

import com.raaspal.robotrecommendation.casereport.adapters.monday.MondayBoardReader;
import com.raaspal.robotrecommendation.casereport.adapters.monday.MondayBoardReader.FilterRule;
import com.raaspal.robotrecommendation.casereport.adapters.monday.dto.MondayItem;
import com.raaspal.robotrecommendation.casereport.adapters.monday.dto.MondayUpdate;
import com.raaspal.robotrecommendation.casereport.service.CaseTicketSyncService;
import com.raaspal.robotrecommendation.casereport.service.CaseTicketSyncService.Baseline;
import com.raaspal.robotrecommendation.casereport.service.CaseTicketSyncService.BoardSpec;
import com.raaspal.robotrecommendation.casereport.service.CaseTicketSyncService.Stamp;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Pulls one brand's tickets - every group, open and done - into {@code case_ticket}.
 *
 * <p>The daily open-group sync only ever sees the "All Case" group, which is right for
 * the pending reports and useless for a brand review: three quarters of AutoXing's
 * tickets are in the Done groups. This asks monday for just that brand's rows with a
 * server-side filter.
 *
 * <p><b>Incremental.</b> A brand's history is mostly settled - of Gausium's 2,600 tickets,
 * about 20 change on a given day - so a run first lists the brand's rows as stamps
 * (id, {@code updated_at}, group, newest comment ids), compares them with what is stored,
 * and fetches in full only the rows that are new or moved. A first load is the same run
 * with nothing stored yet: everything is "new", fetched and written a hundred rows at a
 * time, each hundred its own transaction.
 *
 * <p>Runs after the daily report freeze in {@code CaseReportDailyScheduler}, and on
 * demand from the console's Refresh button. The last outcome is kept in memory for the
 * status endpoint; the durable record is the tickets' own {@code last_synced_at}.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class BrandTicketSyncService {

    static final String ALREADY_RUNNING = "A sync for this brand is already running";

    private final BrandTicketProperties properties;
    private final MondayBoardReader boardReader;
    private final CaseTicketSyncService syncService;

    /**
     * What the last run for a brand did, or why it failed. {@code changed} is how many
     * rows were fetched in full; the rest of {@code result.seen()} were already current.
     */
    public record LastRun(LocalDateTime startedAt, LocalDateTime finishedAt,
                          CaseTicketSyncService.SyncResult result, int changed, String error) {
        public boolean ok() {
            return error == null;
        }
    }

    private final Map<String, LastRun> lastRuns = new ConcurrentHashMap<>();

    /**
     * Brands with a sync in progress. Two at once - Refresh pressed during the nightly
     * run, or pressed twice - would both insert the same new tickets, and the second
     * would fail on {@code uq_case_ticket_source_item} and roll its chunk back.
     */
    private final Set<String> running = ConcurrentHashMap.newKeySet();

    public LastRun lastRun(String brandKey) {
        return lastRuns.get(brandKey);
    }

    public boolean isRunning(String brandKey) {
        return running.contains(brandKey);
    }

    /** Every configured brand, each on its own; one failing does not stop the next. */
    public List<LastRun> syncAll() {
        List<LastRun> runs = new ArrayList<>();
        for (BrandTicketProperties.Brand brand : properties.getBrands()) {
            runs.add(sync(brand));
        }
        return runs;
    }

    /**
     * Brings one brand up to date. If a sync of the same brand is already running this
     * returns at once with an error saying so, rather than starting a second.
     */
    public LastRun sync(BrandTicketProperties.Brand brand) {
        LocalDateTime started = LocalDateTime.now();
        if (!running.add(brand.getKey())) {
            log.info("Brand sync {} skipped: one is already running", brand.getKey());
            return new LastRun(started, started, null, 0, ALREADY_RUNNING);
        }
        LastRun run;
        try {
            BoardSpec spec = CaseTicketSyncService.boardSpec(brand.getBoardId())
                    .orElseThrow(() -> new IllegalStateException("No column mapping for board "
                            + brand.getBoardId() + " (brand " + brand.getKey() + ")"));

            List<MondayItem> stamps = boardReader.readFilteredStamps(brand.getBoardId(), rulesFor(brand));
            Baseline baseline = syncService.baseline(brand.getBoardId());
            List<String> changed = new ArrayList<>();
            List<String> unchanged = new ArrayList<>();
            for (MondayItem stamp : stamps) {
                (hasChanged(stamp, baseline) ? changed : unchanged).add(stamp.id());
            }

            int created = 0, updated = 0, newComments = 0, statusChanges = 0;
            for (int i = 0; i < changed.size(); i += MondayBoardReader.MAX_ITEMS_BY_ID) {
                List<String> chunk = changed.subList(i, Math.min(i + MondayBoardReader.MAX_ITEMS_BY_ID, changed.size()));
                CaseTicketSyncService.SyncResult part = syncService.upsertFiltered(
                        brand.getBoardId(), brand.getOpenGroupId(),
                        boardReader.readItems(chunk, spec.columnIds()),
                        spec.statusColumn(), spec.supStatusColumn(), spec.openDateColumn());
                created += part.created();
                updated += part.updated();
                newComments += part.newComments();
                statusChanges += part.statusChanges();
                log.info("Brand sync {}: {} of {} changed tickets written", brand.getKey(),
                        Math.min(i + MondayBoardReader.MAX_ITEMS_BY_ID, changed.size()), changed.size());
            }
            syncService.touchSynced(brand.getBoardId(), unchanged, LocalDateTime.now());

            CaseTicketSyncService.SyncResult result = new CaseTicketSyncService.SyncResult(
                    brand.getBoardId(), stamps.size(), created, updated, 0, newComments, statusChanges);
            run = new LastRun(started, LocalDateTime.now(), result, changed.size(), null);
            log.info("Brand sync {}: {} tickets on monday, {} changed ({} new), {} new comments, {} status changes",
                    brand.getKey(), stamps.size(), changed.size(), created, newComments, statusChanges);
        } catch (Exception e) {
            run = new LastRun(started, LocalDateTime.now(), null, 0, e.getMessage());
            log.error("Brand sync {} failed", brand.getKey(), e);
        } finally {
            running.remove(brand.getKey());
        }
        lastRuns.put(brand.getKey(), run);
        return run;
    }

    /**
     * Whether a listed row needs fetching in full: not stored yet, edited since it was
     * stored, moved to another group, or carrying a comment or reply not stored yet.
     *
     * <p>The comment test is not redundant with {@code updated_at}: posting a comment
     * does not always move it. Only the newest three comments are listed, so a reply to
     * an older comment is missed until something else moves the row.
     */
    static boolean hasChanged(MondayItem stamp, Baseline baseline) {
        Stamp stored = baseline.tickets().get(stamp.id());
        if (stored == null) return true;

        LocalDateTime updatedAt = stamp.updatedAt() == null ? null
                : stamp.updatedAt().withOffsetSameInstant(ZoneOffset.UTC).toLocalDateTime();
        if (updatedAt != null && (stored.sourceUpdatedAt() == null || updatedAt.isAfter(stored.sourceUpdatedAt()))) {
            return true;
        }

        String groupId = stamp.group() == null ? null : stamp.group().id();
        if (!Objects.equals(groupId, stored.sourceGroupId())) return true;

        if (stamp.updates() != null) {
            for (MondayUpdate update : stamp.updates()) {
                if (update.id() != null && !baseline.commentIds().contains(update.id())) return true;
                if (update.replies() != null) {
                    for (MondayUpdate reply : update.replies()) {
                        if (reply.id() != null && !baseline.commentIds().contains(reply.id())) return true;
                    }
                }
            }
        }
        return false;
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
