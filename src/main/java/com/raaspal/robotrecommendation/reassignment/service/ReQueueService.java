package com.raaspal.robotrecommendation.reassignment.service;

import com.raaspal.robotrecommendation.reassignment.config.ReAssignmentProperties;
import com.raaspal.robotrecommendation.reassignment.dto.ReDtos.AssignmentView;
import com.raaspal.robotrecommendation.reassignment.dto.ReDtos.QueueRow;
import com.raaspal.robotrecommendation.reassignment.dto.ReDtos.QueueView;
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
 * Builds the assignment queue: loads tickets, engineers, levels, mappings, leave, bookings and
 * approvals from the database and hands them to the pure {@link ReAssignmentEvaluator}.
 *
 * <p>Evaluations are computed on every read rather than stored: they depend on load and
 * leave that change by the hour, and a stored suggestion would go stale. What is stored is
 * the decision - an approval keeps the full evaluation it was based on.
 */
@Service
@RequiredArgsConstructor
public class ReQueueService {

    private final ReAssignmentProperties props;
    private final ReTicketRepository tickets;
    private final ReEngineerRepository engineers;
    private final ReModelMappingRepository mappings;
    private final ReSkillDefinitionRepository skills;
    private final ReLeaveRepository leaves;
    private final ReScheduleRepository schedules;
    private final ReAssignmentRepository assignments;
    private final ReTicketHoldRepository holds;
    private final ReSkillMatrixService matrix;
    private final ReTicketRefreshService refresh;

    @Transactional(readOnly = true)
    public List<Evaluation> evaluate() {
        return ReAssignmentEvaluator.evaluate(input());
    }

    /** The evaluation of one ticket, computed with the whole queue so provisional load is right. */
    @Transactional(readOnly = true)
    public Optional<Evaluation> evaluate(String itemId) {
        return evaluate().stream().filter(e -> e.ticket().itemId().equals(itemId)).findFirst();
    }

    @Transactional(readOnly = true)
    public Map<UUID, Workload> workload() {
        Input in = input();
        return ReAssignmentEvaluator.workload(in.tickets(), in.engineers(), in.policy());
    }

    @Transactional(readOnly = true)
    public QueueView queue(boolean canManage) {
        List<Evaluation> evaluations = evaluate();
        Map<UUID, ReEngineer> byId = new HashMap<>();
        engineers.findAll().forEach(e -> byId.put(e.getId(), e));
        Map<String, ReAssignment> current = new HashMap<>();
        assignments.findByBoardIdAndEndedAtIsNull(props.getBoardId()).forEach(a -> current.put(a.getItemId(), a));

        Map<String, Integer> counts = new LinkedHashMap<>();
        for (Outcome o : Outcome.values()) counts.put(o.name(), 0);
        List<QueueRow> rows = new ArrayList<>();
        for (Evaluation e : evaluations) {
            counts.merge(e.outcome().name(), 1, Integer::sum);
            Ticket t = e.ticket();
            ReAssignment a = current.get(t.itemId());
            rows.add(new QueueRow(t.itemId(), t.name(), t.group(), t.status(), t.subStatus(), t.modelLabel(),
                    e.modelName(), t.issueLevel(), t.caseType(), t.serviceMode(), t.serial(), t.customer(),
                    t.branch(), t.mainIssue(), t.openDate(), t.actionDate(),
                    t.people().stream().map(p -> p.name() == null ? p.id() : p.name()).toList(),
                    e.outcome().name(), e.reason(), e.requiredLevel(), e.assumedDifficulty(), e.issueCategory(),
                    canManage ? e.suggested() : redact(e.suggested()),
                    canManage ? e.alternatives() : List.of(),
                    canManage ? e.excluded() : List.of(),
                    a == null ? null : view(a, byId, t.name()), mondayUrl(t.itemId()), e.forDate(), t.zone()));
        }
        List<ReEngineer> active = engineers.findByActiveTrue();
        int withoutMonday = (int) active.stream().filter(x -> x.getMondayUserId() == null).count();
        return new QueueView(rows, counts, refresh.lastRefreshAt(), rows.size(), props.getEmail().isEnabled(),
                canManage, active.size(), withoutMonday, props.getMondayWrite().isEnabled());
    }

    /** Team members without manage rights see who is suggested, not the levels behind it. */
    private static Candidate redact(Candidate c) {
        if (c == null) return null;
        return new Candidate(c.engineerId(), c.name(), null, null, null, null, null, null, null, Map.of(), false);
    }

    public AssignmentView view(ReAssignment a, Map<UUID, ReEngineer> byId, String ticketName) {
        ReEngineer e = byId.get(a.getEngineerId());
        return new AssignmentView(a.getId(), a.getItemId(), ticketName, a.getEngineerId(),
                e == null ? null : e.displayName(), a.getStatus(), a.getOrigin(), a.getScore(),
                a.getRequiredLevel() == null ? null : a.getRequiredLevel().intValue(), a.getReason(),
                a.getApprovedBy(), a.getApprovedAt(), a.getConfirmedAt(), a.getEndedAt(), a.getEndedBy(),
                a.getEmailStatus(), a.getEmailDetail(), a.getMondayStatus(), a.getMondayDetail());
    }

    public String mondayUrl(String itemId) {
        String base = props.getMondayWebUrl();
        if (base == null || base.isBlank()) return null;
        return base.replaceAll("/+$", "") + "/boards/" + props.getBoardId() + "/pulses/" + itemId;
    }

    /* ─── Input ───────────────────────────────────────────────────────────── */

    Input input() {
        String board = props.getBoardId();
        LocalDate today = LocalDate.now(ReEngineerService.BANGKOK);
        Instant now = Instant.now();

        List<Ticket> ticketFacts = tickets.findByBoardIdAndOpenTrue(board).stream()
                .map(t -> new Ticket(t.getItemId(), t.getItemName(), t.getGroupTitle(), t.getStatus(),
                        t.getSubStatus(), t.getModelLabel(), t.getIssueLevel(), t.getCaseType(), t.getServiceMode(),
                        t.getSerialNumber(), t.getCustomer(), t.getBranch(), t.getMainIssue(), t.getOpenDate(),
                        t.getActionDate(), refresh.parsePeople(t.getPeople()), t.getFirstSeenAt(),
                        props.zoneOf(t.getItemName(), t.getBranch(), t.getCustomer())))
                .toList();

        Map<UUID, Map<String, Integer>> levels = matrix.currentLevels();
        // Leave and console bookings from today on. A booking for a ticket that is no longer
        // open stops counting: the job is done, so the engineer is free again.
        Map<String, String> openNames = new HashMap<>();
        ticketFacts.forEach(t -> openNames.put(t.itemId(), t.name()));
        Map<UUID, List<Busy>> busy = new HashMap<>();
        leaves.findByEndsOnGreaterThanEqualOrderByStartsOnAsc(today).forEach(l -> busy
                .computeIfAbsent(l.getEngineerId(), k -> new ArrayList<>())
                .add(new Busy(l.getStartsOn(), l.getEndsOn(), "LEAVE", "leave")));
        schedules.findByEndsOnGreaterThanEqualOrderByStartsOnAsc(today).forEach(s -> {
            if (s.getItemId() != null && !openNames.containsKey(s.getItemId())) return;
            String label = s.getItemId() != null ? "booked: " + openNames.get(s.getItemId())
                    : "booked" + (s.getNote() == null || s.getNote().isBlank() ? "" : ": " + s.getNote());
            busy.computeIfAbsent(s.getEngineerId(), k -> new ArrayList<>())
                    .add(new Busy(s.getStartsOn(), s.getEndsOn(), "JOB", label));
        });
        Map<UUID, Instant> lastApproved = new HashMap<>();
        assignments.findTop200ByBoardIdOrderByApprovedAtDesc(board)
                .forEach(a -> lastApproved.putIfAbsent(a.getEngineerId(), a.getApprovedAt()));

        List<Engineer> engineerFacts = engineers.findAll().stream()
                .map(e -> new Engineer(e.getId(), e.displayName(), e.isActive(), e.getMondayUserId(),
                        e.getEmail() != null, e.getMaxLoad(), levels.getOrDefault(e.getId(), Map.of()),
                        busy.getOrDefault(e.getId(), List.of()), lastApproved.get(e.getId()), e.getHomeZone()))
                .toList();

        Map<String, String> modelNames = new HashMap<>();
        skills.findAll().forEach(s -> modelNames.put(s.getCode(), s.getLabel()));
        Map<String, Mapping> mappingFacts = new HashMap<>();
        mappings.findByBoardIdOrderByDispositionAscLabelAsc(board).forEach(m -> mappingFacts.put(m.getLabel(),
                new Mapping(m.getDisposition(), m.getSkillCode(),
                        m.getSkillCode() == null ? null : modelNames.get(m.getSkillCode()))));

        Map<String, Existing> existing = new HashMap<>();
        assignments.findByBoardIdAndEndedAtIsNull(board).forEach(a ->
                existing.put(a.getItemId(), new Existing(a.getId(), a.getEngineerId(), a.getStatus())));
        Set<String> held = new HashSet<>();
        holds.findByBoardIdAndReleasedAtIsNull(board).forEach(h -> held.add(h.getItemId()));

        return new Input(ticketFacts, engineerFacts, mappingFacts, existing, held, policy(), today, now);
    }

    Policy policy() {
        return new Policy(
                ReTicketRefreshService.normSet(props.getActiveGroups()),
                ReTicketRefreshService.normSet(props.getClosedStatuses()),
                ReTicketRefreshService.normSet(props.getNonCmCaseTypes()),
                props.getUrgencyOrder(),
                props.statusLoadMap(),
                props.getDefaultLoad(),
                props.getNewTicketLoad(),
                props.requiredLevelMap(),
                props.getAssumedIssueLevel(),
                ReTicketRefreshService.normSet(props.getBusyServiceModes()));
    }
}
