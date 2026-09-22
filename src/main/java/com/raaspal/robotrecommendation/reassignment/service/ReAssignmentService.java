package com.raaspal.robotrecommendation.reassignment.service;

import com.raaspal.robotrecommendation.common.exception.BadRequestException;
import com.raaspal.robotrecommendation.common.exception.ResourceNotFoundException;
import com.raaspal.robotrecommendation.reassignment.config.ReAssignmentProperties;
import com.raaspal.robotrecommendation.reassignment.dto.ReDtos.*;
import com.raaspal.robotrecommendation.reassignment.entity.*;
import com.raaspal.robotrecommendation.reassignment.repository.*;
import com.raaspal.robotrecommendation.reassignment.service.ReAssignmentEvaluator.*;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.time.LocalDate;
import java.util.*;

/**
 * The Senior RE's decisions: approve a suggestion or an alternative, assign someone by
 * hand, withdraw an approval, and take a ticket out of (or back into) auto-suggestion.
 *
 * <p>Approval re-evaluates the ticket against current data first - load and leave may have
 * moved since the page was opened - and keeps that evaluation with the assignment.
 * Choosing someone the rules excluded is allowed (the Senior RE knows things the data does
 * not) but needs a reason, and the exclusion is recorded next to it.
 *
 * <p>With monday writes on (the default), approving also sets the engineer as the ticket's
 * RE on monday, so the approval is CONFIRMED at once; the column is re-read right before the
 * write, and if monday refuses, nothing is saved. Cancelling empties the column again when
 * it still shows only that engineer. With writes off, approvals are only recorded and a
 * refresh confirms them once the Senior RE sets monday by hand.
 *
 * <p>An approval can also book the engineer for a range of days (a job that runs several
 * days), so they are not suggested for other work on those days.
 */
@Service
@RequiredArgsConstructor
public class ReAssignmentService {

    private final ReAssignmentProperties props;
    private final ReQueueService queue;
    private final ReAssignmentRepository assignments;
    private final ReEngineerRepository engineers;
    private final ReTicketRepository tickets;
    private final ReTicketHoldRepository holds;
    private final ReAssignmentEmailService email;
    private final ReEventLog events;
    private final ReMondayWriter mondayWriter;
    private final ReScheduleRepository schedules;
    private final ReTicketRefreshService refresh;

    @Transactional
    public ReAssignment approve(ApproveRequest req, String actor) {
        String board = props.getBoardId();
        Evaluation ev = queue.evaluate(req.itemId())
                .orElseThrow(() -> new BadRequestException("Ticket " + req.itemId() + " is not in the open queue - refresh first"));
        if (ev.outcome() == Outcome.ASSIGNED) {
            throw new BadRequestException("monday already has an RE on this ticket");
        }
        if (assignments.findByBoardIdAndItemIdAndEndedAtIsNull(board, req.itemId()).isPresent()) {
            throw new BadRequestException("This ticket already has an approved assignment - cancel it first");
        }
        ReEngineer engineer = engineers.findById(req.engineerId())
                .orElseThrow(() -> new ResourceNotFoundException("Engineer", "id", req.engineerId()));
        if (!engineer.isActive()) throw new BadRequestException(engineer.displayName() + " is inactive");

        Candidate chosen = null;
        String origin;
        if (ev.suggested() != null && ev.suggested().engineerId().equals(engineer.getId())) {
            chosen = ev.suggested();
            origin = "SUGGESTION";
        } else {
            chosen = ev.alternatives().stream().filter(c -> c.engineerId().equals(engineer.getId())).findFirst().orElse(null);
            origin = chosen != null ? "ALTERNATIVE" : "MANUAL";
        }
        Exclusion exclusion = ev.excluded().stream().filter(x -> x.engineerId().equals(engineer.getId())).findFirst().orElse(null);
        String reason = req.reason() == null ? "" : req.reason().trim();
        if (!"SUGGESTION".equals(origin) && reason.isBlank()) {
            throw new BadRequestException(exclusion != null
                    ? "A reason is required to choose someone the rules excluded (" + exclusion.reason() + ")"
                    : "A reason is required when not taking the suggestion");
        }
        LocalDate from = req.bookedFrom();
        LocalDate to = req.bookedTo();
        if ((from == null) != (to == null)) throw new BadRequestException("Give both booking dates, or neither");
        if (from != null && to.isBefore(from)) throw new BadRequestException("The booking ends before it starts");

        // monday first: if it refuses, nothing is recorded and the queue stays as it was.
        boolean written = false;
        if (mondayWriter.enabled()) {
            if (engineer.getMondayUserId() == null || engineer.getMondayUserId().isBlank()) {
                throw new BadRequestException(engineer.displayName() + " is not linked to a monday person yet - "
                        + "link them on the Engineers tab, so approving can set them as RE on monday");
            }
            if (!mondayPeople(req.itemId()).isEmpty()) {
                throw new BadRequestException("Someone was set as RE on monday since the last refresh - refresh to see who");
            }
            try {
                mondayWriter.setPerson(req.itemId(), engineer.getMondayUserId());
            } catch (RuntimeException e) {
                throw new BadRequestException("monday did not accept the change, so nothing was saved: " + e.getMessage());
            }
            written = true;
        }

        Map<String, Object> snapshot = new LinkedHashMap<>();
        snapshot.put("outcome", ev.outcome().name());
        snapshot.put("evaluationReason", ev.reason());
        snapshot.put("model", ev.modelName());
        snapshot.put("modelSkill", ev.modelSkill());
        snapshot.put("requiredLevel", ev.requiredLevel());
        snapshot.put("assumedDifficulty", ev.assumedDifficulty());
        snapshot.put("issueCategory", ev.issueCategory());
        snapshot.put("chosen", chosen);
        snapshot.put("chosenExclusion", exclusion);
        snapshot.put("suggested", ev.suggested());
        snapshot.put("alternatives", ev.alternatives());
        snapshot.put("excluded", ev.excluded());
        snapshot.put("ticket", ev.ticket());

        ReAssignment saved = assignments.save(ReAssignment.builder()
                .boardId(board).itemId(req.itemId()).engineerId(engineer.getId())
                .status(written ? ReAssignment.CONFIRMED : ReAssignment.APPROVED).origin(origin)
                .confirmedAt(written ? Instant.now() : null)
                .mondayStatus(written ? ReAssignment.MONDAY_WRITTEN : ReAssignment.MONDAY_NOT_WRITTEN)
                .mondayWrittenAt(written ? Instant.now() : null)
                .score(chosen == null ? null : chosen.score())
                .requiredLevel(ev.requiredLevel() == null ? null : ev.requiredLevel().shortValue())
                .reason(reason.isBlank() ? ev.reason() : reason)
                .decisionSnapshot(events.json(snapshot))
                .approvedBy(actor).build());

        if (written) {
            // Show it as assigned straight away instead of waiting for the next refresh.
            tickets.findById(new ReTicket.Key(board, req.itemId())).ifPresent(t -> {
                t.setPeople(refresh.peopleJson(List.of(new Person(engineer.getMondayUserId(), engineer.displayName()))));
                tickets.save(t);
            });
        }
        if (from != null) {
            schedules.save(ReSchedule.builder().engineerId(engineer.getId()).boardId(board).itemId(req.itemId())
                    .assignmentId(saved.getId()).startsOn(from).endsOn(to).note("booked with the approval")
                    .createdBy(actor).build());
        }

        holds.findByBoardIdAndItemIdAndReleasedAtIsNull(board, req.itemId()).ifPresent(h -> {
            h.setReleasedAt(Instant.now());
            h.setReleasedBy(actor + " (assigned)");
            holds.save(h);
        });
        Map<String, Object> detail = new LinkedHashMap<>();
        detail.put("itemId", req.itemId());
        detail.put("engineerId", engineer.getId());
        detail.put("engineer", engineer.displayName());
        detail.put("origin", origin);
        detail.put("reason", reason);
        detail.put("mondayWritten", written);
        if (from != null) detail.put("booked", from + " to " + to);
        events.record("ASSIGNMENT", saved.getId(), "APPROVED", actor, detail);
        return saved;
    }

    /** Sends (or re-sends) the assignment email and records how it went. Never throws. */
    @Transactional
    public ReAssignment notifyEngineer(UUID assignmentId, String actor) {
        ReAssignment a = assignments.findById(assignmentId)
                .orElseThrow(() -> new ResourceNotFoundException("Assignment", "id", assignmentId));
        ReEngineer e = engineers.findById(a.getEngineerId()).orElseThrow();
        ReTicket t = tickets.findById(new ReTicket.Key(a.getBoardId(), a.getItemId())).orElse(null);
        if (t == null) {
            a.setEmailStatus("FAILED");
            a.setEmailDetail("Ticket not found locally - refresh first");
            return assignments.save(a);
        }
        ReAssignmentEmailService.Outcome out = email.send(a, e, t, queue.mondayUrl(a.getItemId()));
        a.setEmailStatus(out.status());
        a.setEmailDetail(out.detail());
        if ("SENT".equals(out.status())) a.setEmailSentAt(Instant.now());
        events.record("ASSIGNMENT", a.getId(), "EMAIL_" + out.status(), actor, Map.of("detail", out.detail()));
        return assignments.save(a);
    }

    @Transactional
    public ReAssignment cancel(UUID assignmentId, String reason, String actor) {
        ReAssignment a = assignments.findById(assignmentId)
                .orElseThrow(() -> new ResourceNotFoundException("Assignment", "id", assignmentId));
        if (!a.isCurrent()) throw new BadRequestException("This assignment has already ended");
        if (ReAssignment.MONDAY_WRITTEN.equals(a.getMondayStatus())) undoOnMonday(a);
        schedules.deleteAll(schedules.findByAssignmentId(a.getId()));
        a.setStatus(ReAssignment.CANCELLED);
        a.setEndedAt(Instant.now());
        a.setEndedBy(actor);
        events.record("ASSIGNMENT", a.getId(), "CANCELLED", actor, Map.of("reason", reason));
        return assignments.save(a);
    }

    /**
     * Takes the engineer back out of the RE column - only when monday still shows exactly
     * them. If someone changed it in the meantime, monday is left alone and that is recorded.
     */
    private void undoOnMonday(ReAssignment a) {
        ReEngineer e = engineers.findById(a.getEngineerId()).orElse(null);
        List<String> current;
        try {
            current = mondayWriter.currentPeople(a.getItemId());
        } catch (IllegalStateException gone) {
            a.setMondayStatus(ReAssignment.MONDAY_LEFT);
            a.setMondayDetail(gone.getMessage());
            return;
        } catch (RuntimeException ex) {
            throw new BadRequestException("Could not read the ticket from monday, so nothing was cancelled: " + ex.getMessage());
        }
        if (current.isEmpty()) {
            a.setMondayStatus(ReAssignment.MONDAY_CLEARED);
            a.setMondayDetail("The RE column was already empty");
            return;
        }
        if (e == null || e.getMondayUserId() == null || !current.equals(List.of(e.getMondayUserId()))) {
            a.setMondayStatus(ReAssignment.MONDAY_LEFT);
            a.setMondayDetail("monday shows someone else now, so the RE column was left as it is");
            return;
        }
        try {
            mondayWriter.clear(a.getItemId());
        } catch (RuntimeException ex) {
            throw new BadRequestException("monday did not accept clearing the RE, so nothing was cancelled: " + ex.getMessage());
        }
        a.setMondayStatus(ReAssignment.MONDAY_CLEARED);
        a.setMondayDetail(null);
        tickets.findById(new ReTicket.Key(a.getBoardId(), a.getItemId())).ifPresent(t -> {
            t.setPeople("[]");
            tickets.save(t);
        });
    }

    private List<String> mondayPeople(String itemId) {
        try {
            return mondayWriter.currentPeople(itemId);
        } catch (RuntimeException e) {
            throw new BadRequestException("Could not read the ticket from monday, so nothing was saved: " + e.getMessage());
        }
    }

    @Transactional
    public void hold(HoldRequest req, String actor) {
        String board = props.getBoardId();
        if (holds.findByBoardIdAndItemIdAndReleasedAtIsNull(board, req.itemId()).isPresent()) return;
        holds.save(ReTicketHold.builder().boardId(board).itemId(req.itemId()).reason(req.reason().trim())
                .heldBy(actor).build());
        events.record("TICKET", req.itemId(), "HELD", actor, Map.of("reason", req.reason()));
    }

    @Transactional
    public void release(String itemId, String actor) {
        holds.findByBoardIdAndItemIdAndReleasedAtIsNull(props.getBoardId(), itemId).ifPresent(h -> {
            h.setReleasedAt(Instant.now());
            h.setReleasedBy(actor);
            holds.save(h);
            events.record("TICKET", itemId, "RELEASED", actor, Map.of());
        });
    }

    @Transactional(readOnly = true)
    public List<AssignmentView> history() {
        Map<UUID, ReEngineer> byId = new HashMap<>();
        engineers.findAll().forEach(e -> byId.put(e.getId(), e));
        Map<String, String> names = new HashMap<>();
        tickets.findByBoardId(props.getBoardId()).forEach(t -> names.put(t.getItemId(), t.getItemName()));
        return assignments.findTop200ByBoardIdOrderByApprovedAtDesc(props.getBoardId()).stream()
                .map(a -> queue.view(a, byId, names.get(a.getItemId())))
                .toList();
    }
}
