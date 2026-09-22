package com.raaspal.robotrecommendation.reassignment.service;

import com.raaspal.robotrecommendation.common.exception.BadRequestException;
import com.raaspal.robotrecommendation.common.exception.ResourceNotFoundException;
import com.raaspal.robotrecommendation.reassignment.config.ReAssignmentProperties;
import com.raaspal.robotrecommendation.reassignment.dto.ReDtos.*;
import com.raaspal.robotrecommendation.reassignment.entity.*;
import com.raaspal.robotrecommendation.reassignment.repository.*;
import com.raaspal.robotrecommendation.reassignment.service.ReAssignmentEvaluator.Person;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.*;

/**
 * The RE team directory: add and edit engineers, record leave, and list the people seen in
 * the board's RE column so an engineer can be linked to their monday account.
 */
@Service
@RequiredArgsConstructor
public class ReEngineerService {

    static final ZoneId BANGKOK = ZoneId.of("Asia/Bangkok");

    private final ReEngineerRepository engineers;
    private final ReSkillLevelRepository levels;
    private final ReLeaveRepository leaves;
    private final ReTicketRepository tickets;
    private final ReTicketRefreshService refresh;
    private final ReQueueService queue;
    private final ReAssignmentProperties props;
    private final ReEventLog events;

    @Transactional(readOnly = true)
    public List<EngineerView> list() {
        LocalDate today = LocalDate.now(BANGKOK);
        Map<UUID, ReAssignmentEvaluator.Workload> workload = queue.workload();
        Map<String, String> mondayNames = mondayNames();
        Map<UUID, Integer> assessed = new HashMap<>();
        levels.findAll().forEach(l -> {
            if (l.getLevel() != null) assessed.merge(l.getEngineerId(), 1, Integer::sum);
        });
        Set<UUID> onLeave = new HashSet<>();
        leaves.findByEndsOnGreaterThanEqualOrderByStartsOnAsc(today).forEach(l -> {
            if (l.covers(today)) onLeave.add(l.getEngineerId());
        });
        return engineers.findAllByOrderByActiveDescFullNameAsc().stream()
                .map(e -> {
                    ReAssignmentEvaluator.Workload w = workload.get(e.getId());
                    return new EngineerView(e.getId(), e.getFullName(), e.getNickname(), e.displayName(),
                            e.getEmail(), e.getMondayUserId(),
                            e.getMondayUserId() == null ? null : mondayNames.get(e.getMondayUserId()),
                            e.isActive(), e.getMaxLoad(), e.getNote(),
                            w == null ? BigDecimal.ZERO : w.load(), w == null ? 0 : w.openTickets(),
                            assessed.getOrDefault(e.getId(), 0), onLeave.contains(e.getId()));
                })
                .toList();
    }

    @Transactional
    public ReEngineer create(EngineerRequest req, String actor) {
        ReEngineer e = ReEngineer.builder().build();
        apply(e, req);
        ReEngineer saved = engineers.save(e);
        events.record("ENGINEER", saved.getId(), "CREATED", actor, snapshot(saved));
        return saved;
    }

    @Transactional
    public ReEngineer update(UUID id, EngineerRequest req, String actor) {
        ReEngineer e = engineers.findById(id).orElseThrow(() -> new ResourceNotFoundException("Engineer", "id", id));
        Map<String, Object> before = snapshot(e);
        apply(e, req);
        e.setUpdatedAt(Instant.now());
        ReEngineer saved = engineers.save(e);
        events.record("ENGINEER", id, "UPDATED", actor, Map.of("before", before, "after", snapshot(saved)));
        return saved;
    }

    private void apply(ReEngineer e, EngineerRequest req) {
        e.setFullName(req.fullName().trim());
        e.setNickname(blank(req.nickname()));
        e.setEmail(blank(req.email()) == null ? null : req.email().trim().toLowerCase(Locale.ROOT));
        String monday = blank(req.mondayUserId());
        if (monday != null) {
            engineers.findByMondayUserId(monday).filter(other -> !other.getId().equals(e.getId())).ifPresent(other -> {
                throw new BadRequestException("That monday account is already linked to " + other.displayName());
            });
        }
        if (e.getEmail() != null) {
            engineers.findByEmailIgnoreCase(e.getEmail()).filter(other -> !other.getId().equals(e.getId())).ifPresent(other -> {
                throw new BadRequestException("That email already belongs to " + other.displayName());
            });
        }
        e.setMondayUserId(monday);
        if (req.maxLoad() != null) e.setMaxLoad(req.maxLoad());
        e.setNote(blank(req.note()));
        if (req.active() != null) e.setActive(req.active());
    }

    /* ─── Leave ───────────────────────────────────────────────────────────── */

    @Transactional(readOnly = true)
    public List<LeaveView> upcomingLeave() {
        Map<UUID, String> names = new HashMap<>();
        engineers.findAll().forEach(e -> names.put(e.getId(), e.displayName()));
        return leaves.findByEndsOnGreaterThanEqualOrderByStartsOnAsc(LocalDate.now(BANGKOK).minusDays(1)).stream()
                .map(l -> new LeaveView(l.getId(), l.getEngineerId(), names.get(l.getEngineerId()), l.getStartsOn(),
                        l.getEndsOn(), l.getNote(), l.getCreatedBy()))
                .toList();
    }

    @Transactional
    public void addLeave(LeaveRequest req, String actor) {
        if (req.endsOn().isBefore(req.startsOn())) throw new BadRequestException("Leave ends before it starts");
        if (!engineers.existsById(req.engineerId())) throw new ResourceNotFoundException("Engineer", "id", req.engineerId());
        ReLeave saved = leaves.save(ReLeave.builder().engineerId(req.engineerId()).startsOn(req.startsOn())
                .endsOn(req.endsOn()).note(blank(req.note())).createdBy(actor).build());
        events.record("LEAVE", saved.getId(), "CREATED", actor,
                Map.of("engineerId", req.engineerId(), "from", req.startsOn(), "to", req.endsOn()));
    }

    @Transactional
    public void deleteLeave(UUID id, String actor) {
        ReLeave l = leaves.findById(id).orElseThrow(() -> new ResourceNotFoundException("Leave", "id", id));
        leaves.delete(l);
        events.record("LEAVE", id, "DELETED", actor,
                Map.of("engineerId", l.getEngineerId(), "from", l.getStartsOn(), "to", l.getEndsOn()));
    }

    /* ─── monday people ───────────────────────────────────────────────────── */

    /** Everyone in the RE column of an open ticket, with how many they hold and who they are linked to. */
    @Transactional(readOnly = true)
    public List<MondayPerson> mondayPeople() {
        Map<String, String> name = new HashMap<>();
        Map<String, Integer> count = new HashMap<>();
        for (ReTicket t : tickets.findByBoardIdAndOpenTrue(props.getBoardId())) {
            for (Person p : refresh.parsePeople(t.getPeople())) {
                if (p.name() != null) name.putIfAbsent(p.id(), p.name());
                count.merge(p.id(), 1, Integer::sum);
            }
        }
        Map<String, UUID> linked = new HashMap<>();
        engineers.findAll().forEach(e -> {
            if (e.getMondayUserId() != null) linked.put(e.getMondayUserId(), e.getId());
        });
        return count.keySet().stream()
                .map(id -> new MondayPerson(id, name.get(id), count.get(id), linked.get(id)))
                .sorted(Comparator.comparing((MondayPerson p) -> p.name() == null ? "~" : p.name()))
                .toList();
    }

    private Map<String, String> mondayNames() {
        Map<String, String> out = new HashMap<>();
        for (ReTicket t : tickets.findByBoardIdAndOpenTrue(props.getBoardId())) {
            for (Person p : refresh.parsePeople(t.getPeople())) {
                if (p.name() != null) out.putIfAbsent(p.id(), p.name());
            }
        }
        return out;
    }

    private static Map<String, Object> snapshot(ReEngineer e) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("fullName", e.getFullName());
        m.put("nickname", e.getNickname());
        m.put("email", e.getEmail());
        m.put("mondayUserId", e.getMondayUserId());
        m.put("active", e.isActive());
        m.put("maxLoad", e.getMaxLoad());
        return m;
    }

    static String blank(String s) {
        return s == null || s.isBlank() ? null : s.trim();
    }
}
