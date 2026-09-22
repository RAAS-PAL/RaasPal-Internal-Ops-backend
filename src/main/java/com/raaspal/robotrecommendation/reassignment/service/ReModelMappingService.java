package com.raaspal.robotrecommendation.reassignment.service;

import com.raaspal.robotrecommendation.common.exception.BadRequestException;
import com.raaspal.robotrecommendation.reassignment.config.ReAssignmentProperties;
import com.raaspal.robotrecommendation.reassignment.dto.ReDtos.MappingRequest;
import com.raaspal.robotrecommendation.reassignment.dto.ReDtos.MappingView;
import com.raaspal.robotrecommendation.reassignment.entity.ReModelMapping;
import com.raaspal.robotrecommendation.reassignment.entity.ReSkillDefinition;
import com.raaspal.robotrecommendation.reassignment.entity.ReTicket;
import com.raaspal.robotrecommendation.reassignment.repository.ReModelMappingRepository;
import com.raaspal.robotrecommendation.reassignment.repository.ReSkillDefinitionRepository;
import com.raaspal.robotrecommendation.reassignment.repository.ReTicketRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.*;

/**
 * Which board "Type of Robot" labels count as which matrix model.
 *
 * <p>The list shows every label the board has used on an open ticket - including ones with
 * no mapping row yet, which are treated as MANUAL - so a new robot model appearing on the
 * board is visible here the day it arrives.
 */
@Service
@RequiredArgsConstructor
public class ReModelMappingService {

    private final ReModelMappingRepository mappings;
    private final ReSkillDefinitionRepository skills;
    private final ReTicketRepository tickets;
    private final ReAssignmentProperties props;
    private final ReEventLog events;

    @Transactional(readOnly = true)
    public List<MappingView> list() {
        String board = props.getBoardId();
        Map<String, String> modelNames = modelNames();
        Map<String, Integer> open = new HashMap<>();
        Set<String> active = ReTicketRefreshService.normSet(props.getActiveGroups());
        for (ReTicket t : tickets.findByBoardIdAndOpenTrue(board)) {
            if (t.getModelLabel() != null && active.contains(ReAssignmentProperties.norm(t.getGroupTitle()))) {
                open.merge(t.getModelLabel(), 1, Integer::sum);
            }
        }
        Map<String, MappingView> out = new LinkedHashMap<>();
        for (ReModelMapping m : mappings.findByBoardIdOrderByDispositionAscLabelAsc(board)) {
            out.put(m.getLabel(), new MappingView(m.getLabel(), m.getDisposition(), m.getSkillCode(),
                    m.getSkillCode() == null ? null : modelNames.get(m.getSkillCode()), m.getNote(),
                    open.getOrDefault(m.getLabel(), 0), m.getUpdatedBy(), m.getUpdatedAt()));
        }
        // Labels on open tickets with no row yet: shown as MANUAL so they can be mapped.
        open.forEach((label, count) -> out.computeIfAbsent(label, l -> new MappingView(l, ReModelMapping.MANUAL,
                null, null, "New label - not mapped yet", count, null, null)));
        return out.values().stream()
                .sorted(Comparator.comparing(MappingView::openTickets).reversed().thenComparing(MappingView::label))
                .toList();
    }

    @Transactional
    public MappingView save(MappingRequest req, String actor) {
        String board = props.getBoardId();
        String label = req.label().trim();
        String skill = req.skillCode() == null || req.skillCode().isBlank() ? null : req.skillCode().trim();
        boolean mapped = ReModelMapping.MAPPED.equals(req.disposition());
        if (mapped) {
            ReSkillDefinition d = skill == null ? null : skills.findById(skill).orElse(null);
            if (d == null || !"MODEL".equals(d.getGroupCode()) || !props.getBoardType().equals(d.getBoardType())) {
                throw new BadRequestException("MAPPED needs a " + props.getBoardType().toLowerCase(Locale.ROOT)
                        + " robot model from the skill matrix");
            }
        } else {
            skill = null;
        }
        ReModelMapping m = mappings.findById(new ReModelMapping.Key(board, label))
                .orElse(ReModelMapping.builder().boardId(board).label(label).build());
        Map<String, Object> before = new LinkedHashMap<>();
        before.put("disposition", m.getDisposition());
        before.put("skillCode", m.getSkillCode());
        m.setDisposition(req.disposition());
        m.setSkillCode(skill);
        m.setNote(req.note() == null || req.note().isBlank() ? null : req.note().trim());
        m.setUpdatedBy(actor);
        m.setUpdatedAt(Instant.now());
        mappings.save(m);
        Map<String, Object> after = new LinkedHashMap<>();
        after.put("disposition", m.getDisposition());
        after.put("skillCode", m.getSkillCode());
        events.record("MODEL_MAPPING", board + ":" + label, "SAVED", actor, Map.of("before", before, "after", after));
        return new MappingView(label, m.getDisposition(), skill, skill == null ? null : modelNames().get(skill),
                m.getNote(), 0, actor, m.getUpdatedAt());
    }

    /** Model skill code → display name, for this board's type. */
    Map<String, String> modelNames() {
        Map<String, String> out = new HashMap<>();
        skills.findAll().stream().filter(s -> "MODEL".equals(s.getGroupCode()))
                .forEach(s -> out.put(s.getCode(), s.getLabel()));
        return out;
    }
}
