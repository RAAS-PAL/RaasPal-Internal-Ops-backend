package com.raaspal.robotrecommendation.pm.service;

import com.raaspal.robotrecommendation.casereport.adapters.monday.dto.MondayItem;
import com.raaspal.robotrecommendation.pm.adapter.PmBoardReader;
import com.raaspal.robotrecommendation.pm.config.PmMondayProperties;
import com.raaspal.robotrecommendation.pm.entity.PmContract;
import com.raaspal.robotrecommendation.pm.entity.PmVisit;
import com.raaspal.robotrecommendation.pm.repository.PmContractRepository;
import com.raaspal.robotrecommendation.pm.repository.PmVisitRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Writes one board's contracts and visits, in one transaction.
 *
 * <p>A separate bean from {@link MondayPmSyncService} rather than a method on it,
 * because {@code @Transactional} is applied by a proxy: a call from one method of
 * a bean to another on the same bean never passes through that proxy, so the
 * annotation would have been silently inert. The sync-run bookkeeping stays in
 * the caller for the matching reason - written inside this transaction, the row
 * recording a failure would be rolled back by the failure it describes.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class PmBoardSyncer {

    private final PmBoardReader boardReader;
    private final PmItemMapper mapper;
    private final PmContractRepository contractRepository;
    private final PmVisitRepository visitRepository;

    /** What one board's sync did. */
    public record BoardResult(int contractsRead, int visitsRead, int contractsWritten, int visitsWritten,
                              int orphanSubitems, int markedAbsent) {
    }

    /**
     * Reads a board and its subitem board and mirrors both.
     *
     * <p>All-or-nothing on purpose: a run that wrote contracts but died before their
     * visits would leave the grid showing sites with no work against them, which
     * reads as "nothing due" rather than as a failure.
     */
    @Transactional
    public BoardResult syncBoard(PmMondayProperties.Board board, OffsetDateTime runStart) {
        // Pass 1 - contracts. They must exist before visits can point at them.
        List<MondayItem> parents = boardReader.readBoardItems(board.getId(), board.parentColumnIds());

        Map<String, PmContract> existingContracts = new HashMap<>();
        contractRepository.findBySourceBoardId(board.getId())
                .forEach(contract -> existingContracts.put(contract.getSourceItemId(), contract));

        List<PmContract> contractsToSave = new ArrayList<>();
        Map<String, UUID> contractIdBySourceItem = new HashMap<>();
        for (MondayItem parent : parents) {
            PmContract contract = mapper.toContract(parent, board, existingContracts.get(parent.id()), runStart);
            contractsToSave.add(contract);
            contractIdBySourceItem.put(parent.id(), contract.getId());
        }
        contractRepository.saveAll(contractsToSave);
        contractRepository.flush();
        int contractsAbsent = contractRepository.markAbsentBefore(board.getId(), runStart);

        // Pass 2 - visits, attached by their parent_item.
        List<MondayItem> subitems = boardReader.readSubitems(board.getSubitemBoardId(), board.subitemColumnIds());

        Map<String, PmVisit> existingVisits = new HashMap<>();
        visitRepository.findBySourceBoardId(board.getSubitemBoardId())
                .forEach(visit -> existingVisits.put(visit.getSourceItemId(), visit));

        List<PmVisit> visitsToSave = new ArrayList<>();
        int orphans = 0;
        for (MondayItem subitem : subitems) {
            UUID contractId = contractIdBySourceItem.get(subitem.parentItemId());
            if (contractId == null) {
                // Parent is not on the board this sync read - deleted, moved, or on a
                // board nobody configured. Skipped rather than attached to a guess.
                orphans++;
                continue;
            }
            visitsToSave.add(mapper.toVisit(subitem, board, contractId, existingVisits.get(subitem.id()), runStart));
        }
        visitRepository.saveAll(visitsToSave);
        visitRepository.flush();
        int visitsAbsent = visitRepository.markAbsentBefore(board.getSubitemBoardId(), runStart);

        if (orphans > 0) {
            log.warn("Board {} ({}): {} of {} subitems had no parent on the board and were skipped",
                    board.getId(), board.getServiceLine(), orphans, subitems.size());
        }
        log.info("Board {} ({}): {} contracts, {} visits, {} marked absent",
                board.getId(), board.getServiceLine(), contractsToSave.size(), visitsToSave.size(),
                contractsAbsent + visitsAbsent);

        return new BoardResult(parents.size(), subitems.size(), contractsToSave.size(), visitsToSave.size(),
                orphans, contractsAbsent + visitsAbsent);
    }
}
