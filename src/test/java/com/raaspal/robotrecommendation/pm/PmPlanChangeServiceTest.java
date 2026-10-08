package com.raaspal.robotrecommendation.pm;

import com.raaspal.robotrecommendation.common.exception.BadRequestException;
import com.raaspal.robotrecommendation.pm.adapter.PmMondayWriter;
import com.raaspal.robotrecommendation.pm.adapter.PmMondayWriter.Snapshot;
import com.raaspal.robotrecommendation.pm.config.PmMondayProperties;
import com.raaspal.robotrecommendation.pm.dto.PmPlanChangeView;
import com.raaspal.robotrecommendation.pm.dto.PmPlanDateChange;
import com.raaspal.robotrecommendation.pm.dto.PmPlanDateChange.Outcome;
import com.raaspal.robotrecommendation.pm.entity.PmContract;
import com.raaspal.robotrecommendation.pm.entity.PmPlanChange;
import com.raaspal.robotrecommendation.pm.entity.PmPlanChange.Action;
import com.raaspal.robotrecommendation.pm.entity.PmPlanChange.DateField;
import com.raaspal.robotrecommendation.pm.entity.PmServiceLine;
import com.raaspal.robotrecommendation.pm.entity.PmStatusBucket;
import com.raaspal.robotrecommendation.pm.entity.PmVisit;
import com.raaspal.robotrecommendation.pm.repository.PmContractRepository;
import com.raaspal.robotrecommendation.pm.repository.PmPlanChangeRepository;
import com.raaspal.robotrecommendation.pm.repository.PmVisitRepository;
import com.raaspal.robotrecommendation.pm.service.PmPlanChangeService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.*;

/**
 * Moving a visit from the planner, and undoing it: monday first, never over a date
 * someone changed there, and a completed visit only once the person confirms.
 */
class PmPlanChangeServiceTest {

    private static final String SUBITEM_BOARD = "2444194682";
    private static final String ITEM = "9001";
    private static final String ACTOR = "planner@raaspal.com";
    private static final LocalDate JUNE_1 = LocalDate.of(2026, 6, 1);
    private static final LocalDate JUNE_10 = LocalDate.of(2026, 6, 10);
    private static final LocalDate JUNE_12 = LocalDate.of(2026, 6, 12);
    private static final LocalDate JUNE_17 = LocalDate.of(2026, 6, 17);
    private static final OffsetDateTime NOON = OffsetDateTime.parse("2026-06-01T12:00:00+07:00");

    private PmVisitRepository visits;
    private PmContractRepository contracts;
    private PmPlanChangeRepository changes;
    private PmMondayWriter writer;
    private PmMondayProperties props;
    private PmPlanChangeService service;
    private PmVisit visit;

    @BeforeEach
    void setUp() {
        visits = mock(PmVisitRepository.class);
        contracts = mock(PmContractRepository.class);
        changes = mock(PmPlanChangeRepository.class);
        writer = mock(PmMondayWriter.class);
        when(changes.save(any())).thenAnswer(call -> call.getArgument(0));

        props = new PmMondayProperties();
        PmMondayProperties.Board board = new PmMondayProperties.Board();
        board.setId("2048972900");
        board.setServiceLine(PmServiceLine.CLEANING);
        board.setSubitemBoardId(SUBITEM_BOARD);
        board.getSubitemColumns().setPlanDate("date");
        board.getSubitemColumns().setActionDate("date6");
        board.getSubitemColumns().setStatus("status");
        props.getBoards().add(board);

        service = new PmPlanChangeService(visits, contracts, changes, props, writer);

        visit = PmVisit.builder()
                .id(UUID.randomUUID())
                .pmContractId(UUID.randomUUID())
                .sourceBoardId(SUBITEM_BOARD)
                .sourceItemId(ITEM)
                .planDate(JUNE_1)
                .actionDate(JUNE_10)
                .statusBucket(PmStatusBucket.PLANNED)
                .isPresent(true)
                .build();
        when(visits.findById(visit.getId())).thenReturn(Optional.of(visit));
    }

    /** monday's date and status: for a move, the Action date; for an older move's undo, the Plan date. */
    private void mondayHas(LocalDate date, String time, String status) {
        when(writer.read(SUBITEM_BOARD, ITEM, "date6", "status")).thenReturn(new Snapshot(date, time, status));
        when(writer.read(SUBITEM_BOARD, ITEM, "date", "status")).thenReturn(new Snapshot(date, time, status));
    }

    private PmPlanChange logged() {
        ArgumentCaptor<PmPlanChange> row = ArgumentCaptor.forClass(PmPlanChange.class);
        verify(changes).save(row.capture());
        return row.getValue();
    }

    private void verifyNothingWritten() {
        verify(writer, never()).write(anyString(), anyString(), anyString(), any(), any());
        verify(changes, never()).save(any());
    }

    /* ─── Moving ──────────────────────────────────────────────────────────── */

    @Test
    void writesMondayThenTheMirrorAndRecordsWhoMovedIt() {
        mondayHas(JUNE_10, null, "Planning");

        PmPlanDateChange result = service.move(visit.getId(), JUNE_17, JUNE_10, false, ACTOR);

        assertThat(result.outcome()).isEqualTo(Outcome.MOVED);
        assertThat(result.previousDate()).isEqualTo(JUNE_10);
        assertThat(result.date()).isEqualTo(JUNE_17);
        verify(writer).write(SUBITEM_BOARD, ITEM, "date6", JUNE_17, null);
        assertThat(visit.getActionDate()).isEqualTo(JUNE_17);
        // The Plan date is the contract's: never moved.
        assertThat(visit.getPlanDate()).isEqualTo(JUNE_1);
        verify(visits).save(visit);

        PmPlanChange row = logged();
        assertThat(row.getAction()).isEqualTo(Action.MOVE);
        assertThat(row.getDateField()).isEqualTo(DateField.ACTION);
        assertThat(row.getOldPlanDate()).isEqualTo(JUNE_10);
        assertThat(row.getNewPlanDate()).isEqualTo(JUNE_17);
        assertThat(row.getChangedBy()).isEqualTo(ACTOR);
        assertThat(row.getSourceItemId()).isEqualTo(ITEM);
        assertThat(row.isConfirmedCompleted()).isFalse();
        assertThat(result.changeId()).isEqualTo(row.getId());
    }

    /** A time someone set on monday survives the move. */
    @Test
    void keepsTheTimeOfDay() {
        mondayHas(JUNE_10, "09:30:00", "Planning");

        service.move(visit.getId(), JUNE_17, JUNE_10, false, ACTOR);

        verify(writer).write(SUBITEM_BOARD, ITEM, "date6", JUNE_17, "09:30:00");
    }

    @Test
    void neverOverwritesADateChangedOnMonday() {
        mondayHas(JUNE_12, null, "Planning");

        PmPlanDateChange result = service.move(visit.getId(), JUNE_17, JUNE_10, false, ACTOR);

        assertThat(result.outcome()).isEqualTo(Outcome.CHANGED_ON_MONDAY);
        assertThat(result.date()).isEqualTo(JUNE_12);
        verifyNothingWritten();
        // The mirror catches up, so a reload shows what monday has.
        assertThat(visit.getActionDate()).isEqualTo(JUNE_12);
        verify(visits).save(visit);
    }

    @Test
    void writesNothingWhenTheDateIsTheSame() {
        mondayHas(JUNE_10, null, "Planning");

        PmPlanDateChange result = service.move(visit.getId(), JUNE_10, JUNE_10, false, ACTOR);

        assertThat(result.outcome()).isEqualTo(Outcome.UNCHANGED);
        verifyNothingWritten();
    }

    @Test
    void asksBeforeMovingACompletedVisit() {
        mondayHas(JUNE_10, null, "Done");

        PmPlanDateChange result = service.move(visit.getId(), JUNE_17, JUNE_10, false, ACTOR);

        assertThat(result.outcome()).isEqualTo(Outcome.NEEDS_CONFIRMATION);
        verifyNothingWritten();
    }

    @Test
    void movesACompletedVisitOnceConfirmedAndRecordsTheConfirmation() {
        mondayHas(JUNE_10, null, "Done");

        PmPlanDateChange result = service.move(visit.getId(), JUNE_17, JUNE_10, true, ACTOR);

        assertThat(result.outcome()).isEqualTo(Outcome.MOVED);
        verify(writer).write(SUBITEM_BOARD, ITEM, "date6", JUNE_17, null);
        assertThat(logged().isConfirmedCompleted()).isTrue();
    }

    /** The mirror can be a day old; monday decides whether a visit is completed. */
    @Test
    void judgesCompletionByMondayNotTheMirror() {
        visit.setStatusBucket(PmStatusBucket.COMPLETED);
        mondayHas(JUNE_10, null, "Working on it");

        PmPlanDateChange result = service.move(visit.getId(), JUNE_17, JUNE_10, false, ACTOR);

        assertThat(result.outcome()).isEqualTo(Outcome.MOVED);
    }

    @Test
    void refusesWhenWritingIsSwitchedOff() {
        props.setWriteEnabled(false);

        assertThatThrownBy(() -> service.move(visit.getId(), JUNE_17, JUNE_10, false, ACTOR))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("PM_MONDAY_WRITE_ENABLED");
        verifyNoInteractions(writer);
    }

    @Test
    void refusesAVisitNoLongerOnMonday() {
        visit.setPresent(false);

        assertThatThrownBy(() -> service.move(visit.getId(), JUNE_17, JUNE_10, false, ACTOR))
                .isInstanceOf(IllegalStateException.class);
        verifyNoInteractions(writer);
    }

    @Test
    void requiresANewDate() {
        assertThatThrownBy(() -> service.move(visit.getId(), null, JUNE_10, false, ACTOR))
                .isInstanceOf(BadRequestException.class);
        verifyNoInteractions(writer);
    }

    /* ─── Undoing ─────────────────────────────────────────────────────────── */

    private PmPlanChange move(LocalDate from, LocalDate to, OffsetDateTime at) {
        return PmPlanChange.builder()
                .id(UUID.randomUUID())
                .pmVisitId(visit.getId())
                .sourceBoardId(SUBITEM_BOARD)
                .sourceItemId(ITEM)
                .action(Action.MOVE)
                .oldPlanDate(from)
                .newPlanDate(to)
                .changedBy("someone@raaspal.com")
                .changedAt(at)
                .build();
    }

    private PmPlanChange latestMove(LocalDate from, LocalDate to) {
        PmPlanChange change = move(from, to, NOON);
        when(changes.findById(change.getId())).thenReturn(Optional.of(change));
        when(changes.findFirstByPmVisitIdOrderByChangedAtDesc(visit.getId())).thenReturn(Optional.of(change));
        return change;
    }

    /** A move logged before 2026-10-08 changed the Plan date: undoing it puts the Plan date back. */
    @Test
    void undoPutsTheOldDateBackAndRecordsTheUndo() {
        PmPlanChange moved = latestMove(JUNE_10, JUNE_17);
        visit.setPlanDate(JUNE_17);
        mondayHas(JUNE_17, null, "Planning");

        PmPlanDateChange result = service.undo(moved.getId(), false, ACTOR);

        assertThat(result.outcome()).isEqualTo(Outcome.MOVED);
        verify(writer).write(SUBITEM_BOARD, ITEM, "date", JUNE_10, null);
        assertThat(visit.getPlanDate()).isEqualTo(JUNE_10);
        PmPlanChange row = logged();
        assertThat(row.getAction()).isEqualTo(Action.UNDO);
        assertThat(row.getUndoesChangeId()).isEqualTo(moved.getId());
        assertThat(row.getOldPlanDate()).isEqualTo(JUNE_17);
        assertThat(row.getNewPlanDate()).isEqualTo(JUNE_10);
        assertThat(row.getChangedBy()).isEqualTo(ACTOR);
    }

    /** A visit that had no date before the move goes back to having none. */
    @Test
    void undoCanEmptyTheDateAgain() {
        PmPlanChange moved = latestMove(null, JUNE_17);
        mondayHas(JUNE_17, null, "Planning");

        service.undo(moved.getId(), false, ACTOR);

        verify(writer).write(SUBITEM_BOARD, ITEM, "date", null, null);
    }

    /** A move since 2026-10-08 changed the Action date: its undo puts that back, not the Plan date. */
    @Test
    void undoOfAnActionDateMovePutsTheActionDateBack() {
        PmPlanChange moved = latestMove(JUNE_10, JUNE_17);
        moved.setDateField(DateField.ACTION);
        visit.setActionDate(JUNE_17);
        mondayHas(JUNE_17, null, "Planning");

        PmPlanDateChange result = service.undo(moved.getId(), false, ACTOR);

        assertThat(result.outcome()).isEqualTo(Outcome.MOVED);
        assertThat(result.field()).isEqualTo("ACTION");
        verify(writer).write(SUBITEM_BOARD, ITEM, "date6", JUNE_10, null);
        assertThat(visit.getActionDate()).isEqualTo(JUNE_10);
        assertThat(visit.getPlanDate()).isEqualTo(JUNE_1);
        assertThat(logged().getDateField()).isEqualTo(DateField.ACTION);
    }

    @Test
    void undoStopsWhenMondayChangedSince() {
        PmPlanChange moved = latestMove(JUNE_10, JUNE_17);
        mondayHas(JUNE_12, null, "Planning");

        PmPlanDateChange result = service.undo(moved.getId(), false, ACTOR);

        assertThat(result.outcome()).isEqualTo(Outcome.CHANGED_ON_MONDAY);
        verifyNothingWritten();
    }

    @Test
    void undoOfACompletedVisitAsksFirstToo() {
        PmPlanChange moved = latestMove(JUNE_10, JUNE_17);
        mondayHas(JUNE_17, null, "Done");

        assertThat(service.undo(moved.getId(), false, ACTOR).outcome()).isEqualTo(Outcome.NEEDS_CONFIRMATION);
        verifyNothingWritten();
    }

    @Test
    void refusesToUndoAMoveTwice() {
        PmPlanChange moved = latestMove(JUNE_10, JUNE_17);
        when(changes.existsByUndoesChangeId(moved.getId())).thenReturn(true);

        assertThatThrownBy(() -> service.undo(moved.getId(), false, ACTOR)).isInstanceOf(IllegalStateException.class);
        verifyNoInteractions(writer);
    }

    /** Undoing an older move would silently throw away the moves after it. */
    @Test
    void refusesToUndoAMoveThatWasFollowedByAnother() {
        PmPlanChange older = move(JUNE_10, JUNE_17, NOON.minusHours(1));
        when(changes.findById(older.getId())).thenReturn(Optional.of(older));
        when(changes.findFirstByPmVisitIdOrderByChangedAtDesc(visit.getId()))
                .thenReturn(Optional.of(move(JUNE_17, JUNE_12, NOON)));

        assertThatThrownBy(() -> service.undo(older.getId(), false, ACTOR)).isInstanceOf(IllegalStateException.class);
        verifyNoInteractions(writer);
    }

    @Test
    void refusesToUndoAnUndo() {
        PmPlanChange undo = latestMove(JUNE_17, JUNE_10);
        undo.setAction(Action.UNDO);

        assertThatThrownBy(() -> service.undo(undo.getId(), false, ACTOR)).isInstanceOf(IllegalStateException.class);
        verifyNoInteractions(writer);
    }

    /* ─── Recent moves ────────────────────────────────────────────────────── */

    @Test
    void recentOffersUndoOnlyOnEachVisitsLatestMoveNotYetUndone() {
        PmVisit other = PmVisit.builder().id(UUID.randomUUID()).pmContractId(visit.getPmContractId())
                .visitName("PM2").sourceBoardId(SUBITEM_BOARD).sourceItemId("9002").build();
        visit.setVisitName("PM1");

        PmPlanChange first = move(JUNE_10, JUNE_17, NOON.minusHours(3));
        PmPlanChange second = move(JUNE_17, JUNE_12, NOON.minusHours(2));
        PmPlanChange undoOfSecond = move(JUNE_12, JUNE_17, NOON.minusHours(1));
        undoOfSecond.setAction(Action.UNDO);
        undoOfSecond.setUndoesChangeId(second.getId());
        PmPlanChange otherMove = move(JUNE_10, JUNE_12, NOON);
        otherMove.setPmVisitId(other.getId());

        when(changes.findAllByOrderByChangedAtDesc(any()))
                .thenReturn(List.of(otherMove, undoOfSecond, second, first));
        when(visits.findAllById(any())).thenReturn(List.of(visit, other));
        PmContract site = PmContract.builder().id(visit.getPmContractId()).itemName("Site")
                .serviceLine(PmServiceLine.CLEANING).build();
        when(contracts.findAllById(any())).thenReturn(List.of(site));

        List<PmPlanChangeView> recent = service.recent(50);

        assertThat(recent).extracting(PmPlanChangeView::id)
                .containsExactly(otherMove.getId(), undoOfSecond.getId(), second.getId(), first.getId());
        assertThat(recent).extracting(PmPlanChangeView::undoable).containsExactly(true, false, false, false);
        assertThat(recent).extracting(PmPlanChangeView::undone).containsExactly(false, false, true, false);
        assertThat(recent.get(0).siteName()).isEqualTo("Site");
        assertThat(recent.get(1).visitName()).isEqualTo("PM1");
    }
}
