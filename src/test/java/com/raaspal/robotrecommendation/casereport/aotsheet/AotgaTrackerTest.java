package com.raaspal.robotrecommendation.casereport.aotsheet;

import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/** Where each AOTGA case stands, from the synced sheet and the claims. Made-up data. */
class AotgaTrackerTest {

    private static final LocalDate TODAY = LocalDate.of(2026, 10, 6);
    private static final LocalDateTime SYNCED = LocalDateTime.of(2026, 10, 6, 9, 0);
    private static final String BLUE = "#c9daf8";
    private static final String WHITE = "#ffffff";

    @Test
    void whiteRowsTakeTheirStageFromTheSheet() {
        var view = AotgaTracker.build(List.of(
                row("T-1", true, WHITE, null, null, LocalDate.of(2026, 10, 1)),
                row("T-2", true, WHITE, "Relay board", null, LocalDate.of(2026, 9, 20)),
                row("T-3", true, WHITE, "Brush", LocalDate.of(2026, 10, 2), LocalDate.of(2026, 9, 25))),
                List.of(), props(), TODAY, SYNCED);

        assertThat(view.ready()).isTrue();
        // Oldest issue first.
        assertThat(view.cases()).extracting(AotgaTracker.Case::ticketNo).containsExactly("T-2", "T-3", "T-1");
        // A Spare Part Received date is sent to the manufacturer, of itself: nothing is claimed here.
        assertThat(view.cases()).extracting(AotgaTracker.Case::stage).containsExactly(
                AotgaTracker.Stage.PART_REQUESTED, AotgaTracker.Stage.CLAIMED, AotgaTracker.Stage.REPORTED);
        assertThat(view.cases()).extracting(AotgaTracker.Case::sentFromSheet).containsExactly(false, true, false);
        assertThat(view.cases().get(1).claimedOn()).isNull();
        assertThat(view.cases().get(2).days()).isEqualTo(5);
    }

    /** A colour the team named shows its name on the row; the sheet itself has no such column. */
    @Test
    void aNamedColourShowsItsNameOnTheRow() {
        AotSheetProperties props = props();
        props.setColourLabels(List.of(new AotSheetProperties.ColourLabel("#FFFF00", "Waiting for AOT")));
        var view = AotgaTracker.build(List.of(
                row("T-12", true, "#ffff00", "Relay", null, LocalDate.of(2026, 9, 1)),
                row("T-13", true, WHITE, "Relay", null, LocalDate.of(2026, 9, 2))),
                List.of(), props, TODAY, SYNCED);

        assertThat(view.cases()).extracting(AotgaTracker.Case::colourLabel).containsExactly("Waiting for AOT", null);
        assertThat(view.cases().get(0).colour()).isEqualTo("#ffff00");
    }

    /** A colour marked as meaning nothing: the row reads as if uncoloured, its stage from its columns, no name. */
    @Test
    void anIgnoredColourMeansNothing() {
        AotSheetProperties props = props();
        props.setColourLabels(List.of(AotSheetProperties.ColourLabel.ignore("#ffff00")));
        var view = AotgaTracker.build(List.of(
                row("T-21", true, "#ffff00", "Relay", null, LocalDate.of(2026, 9, 1)),
                row("T-22", true, "#ffff00", null, null, LocalDate.of(2026, 9, 2))),
                List.of(), props, TODAY, SYNCED);

        assertThat(view.cases()).extracting(AotgaTracker.Case::stage)
                .containsExactly(AotgaTracker.Stage.PART_REQUESTED, AotgaTracker.Stage.REPORTED);
        assertThat(view.cases()).extracting(AotgaTracker.Case::colourLabel).containsOnlyNulls();
    }

    /** Every blue row waits for its claim, however long ago it turned blue; the day it turned is kept when seen. */
    @Test
    void everyBlueRowWaitsForItsClaim() {
        var view = AotgaTracker.build(List.of(
                row("T-4", false, BLUE, "Relay", LocalDate.of(2026, 9, 1), LocalDate.of(2026, 8, 20)),
                row("T-5", false, BLUE, null, null, LocalDate.of(2024, 1, 5)),
                row("T-6", false, BLUE, null, null, null)),
                List.of(claim("T-4", LocalDate.of(2026, 10, 3), null)), props(), TODAY, SYNCED);

        assertThat(view.cases()).extracting(AotgaTracker.Case::ticketNo).containsExactly("T-5", "T-4", "T-6");
        assertThat(view.cases()).extracting(AotgaTracker.Case::stage).containsOnly(AotgaTracker.Stage.OLD_PART_BACK);
        assertThat(view.cases()).extracting(AotgaTracker.Case::oldPartBackOn)
                .containsExactly(null, LocalDate.of(2026, 10, 3), null);
    }

    /**
     * AOT asking RAASPAL to look into a case further, not for a part: two steps of its own, by
     * colour alone - a part date or a claim does not move it. Any other request is a part.
     */
    @Test
    void aRequestToLookFurtherFollowsTheReviewSteps() {
        var view = AotgaTracker.build(List.of(
                row("T-31", true, WHITE, "รบกวน RAAS PAL ตรวจสอบเพิ่มเติม", LocalDate.of(2026, 10, 2), LocalDate.of(2026, 9, 1)),
                row("T-32", false, BLUE, "รบกวน RAAS PAL ตรวจสอบ เพิ่มเติม / made-up note", null, LocalDate.of(2026, 9, 2)),
                row("T-33", true, WHITE, "รบกวน RAAS PAL เบิกอะไหล่", null, LocalDate.of(2026, 9, 3)),
                row("T-34", false, BLUE, "Relay", null, LocalDate.of(2026, 9, 4))),
                List.of(claim("T-32", LocalDate.of(2026, 10, 1), LocalDate.of(2026, 10, 3))), props(), TODAY, SYNCED);

        assertThat(view.cases()).extracting(AotgaTracker.Case::stage).containsExactly(
                AotgaTracker.Stage.PENDING_REVIEW, AotgaTracker.Stage.CASE_CLOSED,
                AotgaTracker.Stage.PART_REQUESTED, AotgaTracker.Stage.OLD_PART_BACK);
        assertThat(view.cases().get(1).claimedOn()).isNull();

        AotSheetCase apart = new AotSheetCase(60, null, null, LocalDate.of(2026, 9, 5), null, "M75", "BKK",
                "made-up problem", "ตรวจสอบเพิ่มเติม", null, null, null, null, true, WHITE, AotSheetCase.State.OPEN);
        var withApart = AotgaTracker.build(List.of(), List.of(), props(), TODAY, SYNCED, List.of(apart), List.of());
        assertThat(withApart.noTicket().get(0).stage()).isEqualTo(AotgaTracker.Stage.PENDING_REVIEW);
    }

    /** A row gone from the sheet is not open either; only its colour says it is not blue. */
    @Test
    void aRowDeletedFromTheSheetIsNotMistakenForABlueOne() {
        var view = AotgaTracker.build(List.of(row("T-8", false, WHITE, null, null, LocalDate.of(2026, 9, 10))),
                List.of(claim("T-8", LocalDate.of(2026, 10, 1), null)), props(), TODAY, SYNCED);

        assertThat(view.cases()).isEmpty();
    }

    /** A case sent to the manufacturer stays on the list, at that stage, however long ago it was sent. */
    @Test
    void aSentCaseStaysOnTheListAtItsOwnStage() {
        var view = AotgaTracker.build(List.of(
                row("T-9", false, BLUE, null, null, LocalDate.of(2026, 9, 1)),
                row("T-10", false, BLUE, null, null, LocalDate.of(2026, 8, 1))),
                List.of(claim("T-9", LocalDate.of(2026, 9, 20), LocalDate.of(2026, 10, 5)),
                        claim("T-10", LocalDate.of(2026, 7, 1), LocalDate.of(2026, 7, 10))),
                props(), TODAY, SYNCED);

        assertThat(view.cases()).extracting(AotgaTracker.Case::ticketNo).containsExactly("T-10", "T-9");
        assertThat(view.cases()).extracting(AotgaTracker.Case::stage).containsOnly(AotgaTracker.Stage.CLAIMED);
        assertThat(view.cases().get(1).claimedOn()).isEqualTo(LocalDate.of(2026, 10, 5));
    }

    /** Rows with no ticket number are listed apart, never tracked: the sheet's row says where to fix them. */
    @Test
    void rowsWithoutATicketNumberAreListedApart() {
        AotSheetCase unnamed = new AotSheetCase(41, null, null, LocalDate.of(2023, 5, 1), null, "M75", "BKK",
                "made-up problem", null, null, null, null, null, true, BLUE, AotSheetCase.State.CLOSED);
        var view = AotgaTracker.build(List.of(), List.of(), props(), TODAY, SYNCED, List.of(unnamed), List.of());

        assertThat(view.cases()).isEmpty();
        assertThat(view.noTicket()).extracting(AotgaTracker.Case::sheetRow).containsExactly(41);
        assertThat(view.noTicket().get(0).stage()).isEqualTo(AotgaTracker.Stage.OLD_PART_BACK);
        assertThat(view.noTicket().get(0).ticketNo()).isNull();
    }

    /**
     * Rows sharing one ticket number cannot be told apart, so they are listed apart too - by
     * ticket, then row, so each pair sits together - rather than dropped.
     */
    @Test
    void rowsSharingATicketNumberAreListedApart() {
        AotSheetCase second = new AotSheetCase(52, "T-20", "T-20", LocalDate.of(2024, 2, 1), null, "M40", "DMK",
                "made-up problem", null, null, null, null, null, false, BLUE, AotSheetCase.State.CLOSED);
        AotSheetCase first = new AotSheetCase(17, "T-20", "T-20", LocalDate.of(2024, 1, 5), null, "M40", "DMK",
                "made-up problem", null, null, null, null, null, false, BLUE, AotSheetCase.State.CLOSED);
        var view = AotgaTracker.build(List.of(), List.of(), props(), TODAY, SYNCED, List.of(), List.of(second, first));

        assertThat(view.cases()).isEmpty();
        assertThat(view.repeated()).extracting(AotgaTracker.Case::sheetRow).containsExactly(17, 52);
        assertThat(view.repeated()).extracting(AotgaTracker.Case::ticketNo).containsOnly("T-20");
        assertThat(view.repeated()).extracting(AotgaTracker.Case::stage).containsOnly(AotgaTracker.Stage.OLD_PART_BACK);
    }

    /**
     * A past day is read from the copy kept that day. A day with no copy - before history
     * started - gets today's list, marked as standing in for it.
     */
    @Test
    void aPastDayComesFromItsCopyAndADayBeforeHistoryFromToday() throws Exception {
        var mapper = new com.fasterxml.jackson.databind.ObjectMapper().findAndRegisterModules();
        var snapshots = org.mockito.Mockito.mock(AotgaSnapshotRepository.class);
        var settings = org.mockito.Mockito.mock(AotSheetSettingsService.class);
        var sync = org.mockito.Mockito.mock(AotSheetSyncService.class);
        var sheet = org.mockito.Mockito.mock(AotgaSheetRows.class);
        var claims = org.mockito.Mockito.mock(AotgaClaimRepository.class);
        AotSheetProperties props = props();
        props.setSyncEnabled(true);
        org.mockito.Mockito.when(settings.effective()).thenReturn(props);
        org.mockito.Mockito.when(sync.lastSyncedAt()).thenReturn(SYNCED);
        org.mockito.Mockito.when(sync.unidentifiedRows()).thenReturn(List.of());
        org.mockito.Mockito.when(sync.repeatedRows()).thenReturn(List.of());
        org.mockito.Mockito.when(sheet.read()).thenReturn(List.of(
                row("T-15", true, WHITE, null, null, LocalDate.of(2026, 9, 2)),
                row("T-16", true, WHITE, null, null, LocalDate.of(2026, 9, 3))));
        var tracker = new AotgaTracker(settings, sync, sheet, claims, snapshots, mapper);
        var kept = AotgaTracker.build(List.of(row("T-14", true, WHITE, "Relay", null, LocalDate.of(2026, 9, 1))),
                List.of(), props(), LocalDate.of(2026, 9, 30), SYNCED);
        LocalDate first = LocalDate.of(2026, 9, 28);
        org.mockito.Mockito.when(snapshots.findFirstByOrderByRunDateAsc())
                .thenReturn(java.util.Optional.of(AotgaSnapshot.builder().runDate(first).build()));
        org.mockito.Mockito.when(snapshots.findById(LocalDate.of(2026, 9, 30))).thenReturn(java.util.Optional.of(
                AotgaSnapshot.builder().runDate(LocalDate.of(2026, 9, 30)).viewJson(mapper.writeValueAsString(kept)).build()));
        org.mockito.Mockito.when(snapshots.findById(LocalDate.of(2026, 9, 1))).thenReturn(java.util.Optional.empty());

        var past = tracker.view(LocalDate.of(2026, 9, 30));
        var before = tracker.view(LocalDate.of(2026, 9, 1));

        assertThat(past.live()).isFalse();
        assertThat(past.noCopyFor()).isNull();
        assertThat(past.cases()).extracting(AotgaTracker.Case::ticketNo).containsExactly("T-14");
        assertThat(past.historyFrom()).isEqualTo(first);
        assertThat(before.live()).isTrue();
        assertThat(before.noCopyFor()).isEqualTo(LocalDate.of(2026, 9, 1));
        assertThat(before.cases()).extracting(AotgaTracker.Case::ticketNo).containsExactly("T-15", "T-16");
        assertThat(before.historyFrom()).isEqualTo(first);
    }

    private static AotgaSheetRows.Ticket row(String ticket, boolean open, String colour, String requestedPart,
                                             LocalDate partSentOn, LocalDate issued) {
        return new AotgaSheetRows.Ticket("sheet:" + ticket, ticket, "BKK", "M75", null, "made-up problem",
                issued, null, open, colour, requestedPart, partSentOn, 2);
    }

    private static AotgaClaim claim(String ticket, LocalDate back, LocalDate claimedOn) {
        return AotgaClaim.builder().spreadsheetId("sheet-abc").ticketNo(ticket).oldPartBackOn(back)
                .claimedOn(claimedOn).note(claimedOn == null ? null : "claim note").claimedBy("tester@example.com")
                .createdAt(OffsetDateTime.now()).updatedAt(OffsetDateTime.now()).build();
    }

    private static AotSheetProperties props() {
        AotSheetProperties props = new AotSheetProperties();
        props.setSpreadsheetId("sheet-abc");
        props.setClosedColours(List.of("#C9DAF8"));
        return props;
    }
}
