package com.raaspal.robotrecommendation.reassignment.service;

import com.raaspal.robotrecommendation.common.exception.BadRequestException;
import com.raaspal.robotrecommendation.reassignment.dto.ReDtos.*;
import com.raaspal.robotrecommendation.reassignment.entity.*;
import com.raaspal.robotrecommendation.reassignment.repository.*;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.time.LocalDate;
import java.util.*;

/**
 * The skill matrix: read it, change levels in the console, and apply an import.
 *
 * <p>Every change - one cell or a whole workbook - is a {@link ReMatrixRevision} with a
 * label, a reason and an author, and each changed cell is written to the append-only
 * {@link ReSkillChange} log. The current levels live in {@link ReSkillLevel}; history is
 * never rewritten, so "what did the matrix say when this assignment was approved" stays
 * answerable (the approval also keeps its own copy of the levels it used).
 */
@Service
@RequiredArgsConstructor
public class ReSkillMatrixService {

    private final ReSkillDefinitionRepository skills;
    private final ReSkillLevelRepository levels;
    private final ReSkillChangeRepository changes;
    private final ReMatrixRevisionRepository revisions;
    private final ReEngineerRepository engineers;
    private final ReEventLog events;

    @Transactional(readOnly = true)
    public MatrixView matrix() {
        List<SkillView> skillViews = skills.findAllByOrderByOrdinalAsc().stream()
                .map(s -> new SkillView(s.getCode(), s.getGroupCode(), s.getBoardType(), s.getLabel(), s.getOrdinal()))
                .toList();
        Map<UUID, Map<String, Integer>> byEngineer = currentLevels();
        List<MatrixRow> rows = engineers.findAllByOrderByActiveDescFullNameAsc().stream()
                .map(e -> new MatrixRow(e.getId(), e.displayName(), e.isActive(),
                        byEngineer.getOrDefault(e.getId(), Map.of())))
                .toList();
        return new MatrixView(skillViews, rows, recentRevisions());
    }

    /** engineer → skill → level, only assessed cells. */
    @Transactional(readOnly = true)
    public Map<UUID, Map<String, Integer>> currentLevels() {
        Map<UUID, Map<String, Integer>> out = new HashMap<>();
        levels.findAll().forEach(l -> {
            if (l.getLevel() != null) {
                out.computeIfAbsent(l.getEngineerId(), k -> new HashMap<>()).put(l.getSkillCode(), l.getLevel().intValue());
            }
        });
        return out;
    }

    @Transactional(readOnly = true)
    public List<RevisionView> recentRevisions() {
        return revisions.findTop20ByOrderByCreatedAtDesc().stream()
                .map(r -> new RevisionView(r.getId(), r.getLabel(), r.getSource(), r.getReason(), r.getCreatedBy(),
                        r.getCreatedAt(), changes.countByRevisionId(r.getId())))
                .toList();
    }

    @Transactional(readOnly = true)
    public List<SkillHistoryEntry> history(UUID engineerId) {
        Map<UUID, String> labels = new HashMap<>();
        revisions.findAll().forEach(r -> labels.put(r.getId(), r.getLabel()));
        return changes.findTop50ByEngineerIdOrderByChangedAtDesc(engineerId).stream()
                .map(c -> new SkillHistoryEntry(labels.get(c.getRevisionId()), c.getSkillCode(),
                        toInt(c.getOldLevel()), toInt(c.getNewLevel()), c.getChangedAt()))
                .toList();
    }

    /** A console edit: one revision for the whole batch of cells. */
    @Transactional
    public RevisionView update(MatrixUpdateRequest req, String actor) {
        ReMatrixRevision rev = newRevision(nextConsoleLabel(), "CONSOLE", null, req.reason(), actor);
        int changed = applyChanges(rev, req.changes());
        if (changed == 0) {
            throw new BadRequestException("Nothing changed - every level is already as submitted");
        }
        events.record("SKILL_MATRIX", rev.getId(), "EDITED", actor, Map.of("label", rev.getLabel(), "cells", changed));
        return new RevisionView(rev.getId(), rev.getLabel(), rev.getSource(), rev.getReason(), rev.getCreatedBy(),
                rev.getCreatedAt(), changed);
    }

    /**
     * Writes the changed cells under {@code rev}; unchanged cells are skipped so the change
     * log records only real moves. Returns how many cells changed.
     */
    @Transactional
    public int applyChanges(ReMatrixRevision rev, List<LevelChange> requested) {
        Set<String> known = new HashSet<>();
        skills.findAll().forEach(s -> known.add(s.getCode()));
        Map<ReSkillLevel.Key, ReSkillLevel> current = new HashMap<>();
        levels.findAll().forEach(l -> current.put(new ReSkillLevel.Key(l.getEngineerId(), l.getSkillCode()), l));

        int changed = 0;
        Instant now = Instant.now();
        for (LevelChange c : requested) {
            if (!known.contains(c.skillCode())) throw new BadRequestException("Unknown skill " + c.skillCode());
            if (c.level() != null && (c.level() < 1 || c.level() > 4)) {
                throw new BadRequestException("Levels are L1-L4 or not assessed");
            }
            if (!engineers.existsById(c.engineerId())) throw new BadRequestException("Unknown engineer " + c.engineerId());
            ReSkillLevel.Key key = new ReSkillLevel.Key(c.engineerId(), c.skillCode());
            ReSkillLevel existing = current.get(key);
            Short newLevel = c.level() == null ? null : c.level().shortValue();
            Short oldLevel = existing == null ? null : existing.getLevel();
            if (Objects.equals(oldLevel, newLevel)) continue;

            ReSkillLevel row = existing != null ? existing
                    : ReSkillLevel.builder().engineerId(c.engineerId()).skillCode(c.skillCode()).build();
            row.setLevel(newLevel);
            row.setRevisionId(rev.getId());
            row.setUpdatedAt(now);
            levels.save(row);
            current.put(key, row);
            changes.save(ReSkillChange.builder().revisionId(rev.getId()).engineerId(c.engineerId())
                    .skillCode(c.skillCode()).oldLevel(oldLevel).newLevel(newLevel).changedAt(now).build());
            changed++;
        }
        return changed;
    }

    @Transactional
    public ReMatrixRevision newRevision(String label, String source, String fileHash, String reason, String actor) {
        if (revisions.existsByLabel(label)) throw new BadRequestException("Revision label \"" + label + "\" is already used");
        return revisions.save(ReMatrixRevision.builder().label(label).source(source).fileHash(fileHash)
                .reason(reason).createdBy(actor).build());
    }

    private String nextConsoleLabel() {
        String base = "console-" + LocalDate.now(ReEngineerService.BANGKOK);
        for (int i = 1; ; i++) {
            String label = base + "-" + i;
            if (!revisions.existsByLabel(label)) return label;
        }
    }

    private static Integer toInt(Short s) {
        return s == null ? null : s.intValue();
    }
}
