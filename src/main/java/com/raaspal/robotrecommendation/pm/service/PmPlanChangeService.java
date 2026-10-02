package com.raaspal.robotrecommendation.pm.service;

import com.raaspal.robotrecommendation.common.exception.BadRequestException;
import com.raaspal.robotrecommendation.common.exception.ResourceNotFoundException;
import com.raaspal.robotrecommendation.pm.adapter.PmMondayWriter;
import com.raaspal.robotrecommendation.pm.config.PmMondayProperties;
import com.raaspal.robotrecommendation.pm.dto.PmPlanChangeView;
import com.raaspal.robotrecommendation.pm.dto.PmPlanDateChange;
import com.raaspal.robotrecommendation.pm.dto.PmPlanDateChange.Outcome;
import com.raaspal.robotrecommendation.pm.entity.PmContract;
import com.raaspal.robotrecommendation.pm.entity.PmPlanChange;
import com.raaspal.robotrecommendation.pm.entity.PmPlanChange.Action;
import com.raaspal.robotrecommendation.pm.entity.PmStatusBucket;
import com.raaspal.robotrecommendation.pm.entity.PmVisit;
import com.raaspal.robotrecommendation.pm.repository.PmContractRepository;
import com.raaspal.robotrecommendation.pm.repository.PmPlanChangeRepository;
import com.raaspal.robotrecommendation.pm.repository.PmVisitRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;

import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.*;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * Moves a PM visit to another plan date from the planner, keeps a record of every
 * move, and undoes one.
 *
 * <p>monday stays the one place PM is scheduled: the date is written there first, and
 * the mirror is only updated after monday has taken it, so the grid moves at once
 * instead of waiting for the next sync. Every write, a move or an undo, is guarded the
 * same way:
 * <ul>
 *   <li>the date is read back from monday right before writing, and a date someone
 *       changed there since the planner last synced is never overwritten;</li>
 *   <li>a visit monday shows as completed is only changed once the person confirms,
 *       judged by monday's live status rather than the mirror's, which can be a day old;</li>
 *   <li>{@code app.pm.monday.write-enabled} stops all of it.</li>
 * </ul>
 *
 * <p>Deliberately not one transaction: it would hold a database connection open across
 * two monday calls, and the only row worth keeping from a half-finished move is the
 * mirror catching up with monday, which the next sync would do anyway.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class PmPlanChangeService {

    private final PmVisitRepository visits;
    private final PmContractRepository contracts;
    private final PmPlanChangeRepository changes;
    private final PmMondayProperties props;
    private final PmMondayWriter writer;

    public PmPlanDateChange move(UUID visitId, LocalDate newDate, LocalDate seenPlanDate,
                                 boolean confirmCompleted, String actor) {
        if (newDate == null) {
            throw new BadRequestException("planDate is required");
        }
        PmVisit visit = visits.findById(visitId)
                .orElseThrow(() -> new ResourceNotFoundException("PM visit", "id", visitId));
        return apply(visit, newDate, seenPlanDate, confirmCompleted, actor, null);
    }

    /**
     * Puts a moved visit back on the date it had before.
     *
     * <p>Only a visit's latest change can be undone, and only while monday still holds
     * the date that move set: undoing an older move would silently throw away the ones
     * after it.
     */
    public PmPlanDateChange undo(UUID changeId, boolean confirmCompleted, String actor) {
        PmPlanChange change = changes.findById(changeId)
                .orElseThrow(() -> new ResourceNotFoundException("PM plan change", "id", changeId));
        if (change.getAction() != Action.MOVE) {
            throw new IllegalStateException("An undo cannot itself be undone; move the visit instead");
        }
        if (changes.existsByUndoesChangeId(changeId)) {
            throw new IllegalStateException("This move has already been undone");
        }
        boolean latest = changes.findFirstByPmVisitIdOrderByChangedAtDesc(change.getPmVisitId())
                .map(last -> last.getId().equals(changeId))
                .orElse(false);
        if (!latest) {
            throw new IllegalStateException("The visit was moved again since; undo the later move first");
        }
        PmVisit visit = visits.findById(change.getPmVisitId())
                .orElseThrow(() -> new ResourceNotFoundException("PM visit", "id", change.getPmVisitId()));
        return apply(visit, change.getOldPlanDate(), change.getNewPlanDate(), confirmCompleted, actor, changeId);
    }

    /** The latest moves and undos, newest first, with whether each can still be undone. */
    public List<PmPlanChangeView> recent(int limit) {
        List<PmPlanChange> rows = changes.findAllByOrderByChangedAtDesc(PageRequest.of(0, limit));

        Map<UUID, PmVisit> visitsById = visits.findAllById(
                        rows.stream().map(PmPlanChange::getPmVisitId).collect(Collectors.toSet())).stream()
                .collect(Collectors.toMap(PmVisit::getId, Function.identity()));
        Map<UUID, PmContract> contractsById = contracts.findAllById(
                        visitsById.values().stream().map(PmVisit::getPmContractId).collect(Collectors.toSet())).stream()
                .collect(Collectors.toMap(PmContract::getId, Function.identity()));

        // Newest first, so the first row seen for a visit is its latest change. Any later
        // change of a visit in this window is in the window too, being later.
        Set<UUID> undone = rows.stream().map(PmPlanChange::getUndoesChangeId)
                .filter(Objects::nonNull).collect(Collectors.toSet());
        Set<UUID> seenVisits = new HashSet<>();

        List<PmPlanChangeView> out = new ArrayList<>();
        for (PmPlanChange row : rows) {
            boolean latestForVisit = seenVisits.add(row.getPmVisitId());
            PmVisit visit = visitsById.get(row.getPmVisitId());
            PmContract contract = visit == null ? null : contractsById.get(visit.getPmContractId());
            boolean isUndone = undone.contains(row.getId());
            out.add(new PmPlanChangeView(
                    row.getId(), row.getPmVisitId(), row.getSourceItemId(),
                    visit == null ? null : visit.getVisitName(),
                    contract == null ? null : contract.getItemName(),
                    contract == null || contract.getServiceLine() == null ? null : contract.getServiceLine().name(),
                    row.getAction().name(), row.getOldPlanDate(), row.getNewPlanDate(),
                    row.isConfirmedCompleted(), row.getChangedBy(), row.getChangedAt(),
                    isUndone,
                    row.getAction() == Action.MOVE && !isUndone && latestForVisit && visit != null));
        }
        return out;
    }

    /**
     * Sets a visit's plan date on monday and in the mirror, after the checks every write
     * shares, and logs it.
     *
     * @param expected the date monday must still hold for the write to go ahead
     * @param undoes   the move this reverses; null for a move
     */
    private PmPlanDateChange apply(PmVisit visit, LocalDate target, LocalDate expected,
                                   boolean confirmCompleted, String actor, UUID undoes) {
        if (!visit.isPresent()) {
            throw new IllegalStateException("This visit is no longer on monday");
        }
        if (!props.isWriteEnabled()) {
            throw new IllegalStateException("Moving visits is switched off (PM_MONDAY_WRITE_ENABLED=false)");
        }
        PmMondayProperties.SubitemColumns columns = props.boardForSubitemBoard(visit.getSourceBoardId())
                .orElseThrow(() -> new IllegalStateException(
                        "No PM board is configured for subitem board " + visit.getSourceBoardId()))
                .getSubitemColumns();
        String statusColumn = columns.getStatus() == null || columns.getStatus().isBlank() ? null : columns.getStatus();

        PmMondayWriter.Snapshot now = writer.read(
                visit.getSourceBoardId(), visit.getSourceItemId(), columns.getPlanDate(), statusColumn);

        if (!Objects.equals(now.planDate(), expected)) {
            // Changed on monday since the planner last synced. Catch the mirror up so a
            // reload shows monday's date, and hand the choice back to the person.
            if (!Objects.equals(visit.getPlanDate(), now.planDate())) {
                visit.setPlanDate(now.planDate());
                visits.save(visit);
            }
            return new PmPlanDateChange(visit.getId(), Outcome.CHANGED_ON_MONDAY, expected, now.planDate(), null);
        }
        if (Objects.equals(target, now.planDate())) {
            return new PmPlanDateChange(visit.getId(), Outcome.UNCHANGED, now.planDate(), now.planDate(), null);
        }

        // monday's word on completion, not the mirror's: a visit finished this morning
        // still looks planned on a grid synced last night.
        PmStatusBucket status = statusColumn != null ? PmStatusBucket.fromRaw(now.status()) : visit.getStatusBucket();
        boolean completed = status == PmStatusBucket.COMPLETED;
        if (completed && !confirmCompleted) {
            return new PmPlanDateChange(visit.getId(), Outcome.NEEDS_CONFIRMATION, now.planDate(), now.planDate(), null);
        }

        writer.write(visit.getSourceBoardId(), visit.getSourceItemId(), columns.getPlanDate(), target, now.time());

        visit.setPlanDate(target);
        visits.save(visit);
        PmPlanChange logged = changes.save(PmPlanChange.builder()
                .id(UUID.randomUUID())
                .pmVisitId(visit.getId())
                .sourceBoardId(visit.getSourceBoardId())
                .sourceItemId(visit.getSourceItemId())
                .action(undoes == null ? Action.MOVE : Action.UNDO)
                .undoesChangeId(undoes)
                .oldPlanDate(now.planDate())
                .newPlanDate(target)
                .confirmedCompleted(completed)
                .changedBy(actor)
                .changedAt(OffsetDateTime.now())
                .build());
        log.info("PM visit {} (monday item {}) {} from {} to {} by {}{}",
                visit.getId(), visit.getSourceItemId(), undoes == null ? "moved" : "moved back (undo)",
                now.planDate(), target, actor, completed ? ", confirmed although completed" : "");

        return new PmPlanDateChange(visit.getId(), Outcome.MOVED, now.planDate(), target, logged.getId());
    }
}
