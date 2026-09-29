package com.raaspal.robotrecommendation.casereport.brand;

import com.raaspal.robotrecommendation.casereport.adapters.monday.dto.MondayGroup;
import com.raaspal.robotrecommendation.casereport.adapters.monday.dto.MondayItem;
import com.raaspal.robotrecommendation.casereport.adapters.monday.dto.MondayUpdate;
import com.raaspal.robotrecommendation.casereport.service.CaseTicketSyncService.Baseline;
import com.raaspal.robotrecommendation.casereport.service.CaseTicketSyncService.Stamp;
import org.junit.jupiter.api.Test;

import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * What the incremental brand sync decides to fetch in full. Getting this wrong in the
 * lenient direction costs a slower sync; in the strict direction it silently leaves a
 * ticket stale, which is the one to test hardest.
 */
class BrandTicketSyncServiceTest {

    private static final LocalDateTime STORED_AT = LocalDateTime.of(2026, 9, 28, 3, 0);

    /** One stored ticket "42" in All Case, with comment c1 and its reply r1. */
    private static final Baseline BASELINE = new Baseline(
            Map.of("42", new Stamp(STORED_AT, "all_case")),
            Set.of("c1", "r1"));

    private static MondayItem stamp(String id, LocalDateTime updatedAtUtc, String groupId, List<MondayUpdate> updates) {
        return new MondayItem(id, null,
                updatedAtUtc == null ? null : OffsetDateTime.of(updatedAtUtc, ZoneOffset.UTC),
                new MondayGroup(groupId, null), null, updates, null);
    }

    private static MondayUpdate comment(String id, MondayUpdate... replies) {
        return new MondayUpdate(id, null, null, null, List.of(replies));
    }

    @Test
    void unchangedTicketIsNotFetched() {
        MondayItem same = stamp("42", STORED_AT, "all_case", List.of(comment("c1", comment("r1"))));
        assertThat(BrandTicketSyncService.hasChanged(same, BASELINE)).isFalse();
    }

    @Test
    void ticketNotStoredYetIsFetched() {
        assertThat(BrandTicketSyncService.hasChanged(stamp("99", STORED_AT, "all_case", List.of()), BASELINE)).isTrue();
    }

    @Test
    void editedTicketIsFetched() {
        MondayItem edited = stamp("42", STORED_AT.plusMinutes(5), "all_case", List.of(comment("c1")));
        assertThat(BrandTicketSyncService.hasChanged(edited, BASELINE)).isTrue();
    }

    @Test
    void ticketMovedToAnotherGroupIsFetched() {
        MondayItem moved = stamp("42", STORED_AT, "tickets_done", List.of(comment("c1")));
        assertThat(BrandTicketSyncService.hasChanged(moved, BASELINE)).isTrue();
    }

    /** monday does not always move updated_at for a comment - 28 Gausium tickets showed it. */
    @Test
    void newCommentIsFetchedEvenWhenUpdatedAtDidNotMove() {
        MondayItem commented = stamp("42", STORED_AT, "all_case", List.of(comment("c2"), comment("c1")));
        assertThat(BrandTicketSyncService.hasChanged(commented, BASELINE)).isTrue();
    }

    @Test
    void newReplyIsFetchedEvenWhenUpdatedAtDidNotMove() {
        MondayItem replied = stamp("42", STORED_AT, "all_case", List.of(comment("c1", comment("r1"), comment("r2"))));
        assertThat(BrandTicketSyncService.hasChanged(replied, BASELINE)).isTrue();
    }

    @Test
    void storedRowWithoutUpdatedAtIsRefetchedOnce() {
        Baseline noStamp = new Baseline(Map.of("42", new Stamp(null, "all_case")), Set.of());
        assertThat(BrandTicketSyncService.hasChanged(stamp("42", STORED_AT, "all_case", List.of()), noStamp)).isTrue();
    }
}
