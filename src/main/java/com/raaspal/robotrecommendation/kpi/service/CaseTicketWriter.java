package com.raaspal.robotrecommendation.kpi.service;

import com.raaspal.robotrecommendation.casereport.adapters.monday.dto.MondayItem;
import com.raaspal.robotrecommendation.kpi.config.KpiMondayProperties;
import com.raaspal.robotrecommendation.kpi.entity.KpiCaseTicket;
import com.raaspal.robotrecommendation.kpi.repository.KpiCaseTicketRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * The database half of a board sync: merges a full read of a board into
 * {@code kpi_case_ticket} in one transaction. Kept apart from
 * {@link MondayCaseSyncService} so the monday round-trips happen outside any
 * transaction — a slow board must not hold a pooled connection open, and the
 * Supabase session pooler only allows fifteen.
 *
 * <p>Idempotent: re-running the same read changes nothing but
 * {@code last_synced_at}, which is what makes a nightly full sync safe.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class CaseTicketWriter {

    private final KpiCaseTicketRepository ticketRepository;
    private final CaseTicketMapper mapper;

    /** What one merge did. {@code updated} counts only rows whose monday {@code updated_at} moved. */
    public record WriteResult(int inserted, int updated, int unchanged, int markedAbsent) {
    }

    /**
     * Merges {@code items} into the board's rows.
     *
     * @param markMissingAbsent flip {@code present} off on rows the read did not
     *                          return. Pass false when the read was incomplete,
     *                          or a large group's tail would be "deleted".
     */
    @Transactional
    public WriteResult upsert(KpiMondayProperties.Board board, List<MondayItem> items,
                              boolean markMissingAbsent, LocalDateTime now) {
        Map<String, KpiCaseTicket> existing = new HashMap<>();
        for (KpiCaseTicket ticket : ticketRepository.findAllBySourceAndSourceBoardId(KpiCaseTicket.SOURCE_MONDAY, board.getId())) {
            existing.put(ticket.getSourceItemId(), ticket);
        }

        int inserted = 0;
        int updated = 0;
        int unchanged = 0;
        Set<String> seen = new HashSet<>();
        List<KpiCaseTicket> toSave = new ArrayList<>();

        for (MondayItem item : items) {
            if (item.id() == null || !seen.add(item.id())) {
                continue;
            }
            KpiCaseTicket ticket = existing.get(item.id());
            if (ticket == null) {
                toSave.add(mapper.newTicket(item, board, now));
                inserted++;
                continue;
            }
            // The mapping is always re-applied so a corrected column id takes
            // effect on the next sync; only the count distinguishes "changed".
            boolean changed = !ticket.isPresent()
                    || !Objects.equals(ticket.getSourceUpdatedAt(), CaseTicketMapper.toUtc(item.updatedAt()));
            mapper.apply(item, board, ticket, now);
            toSave.add(ticket);
            if (changed) {
                updated++;
            } else {
                unchanged++;
            }
        }

        int markedAbsent = 0;
        if (markMissingAbsent) {
            for (KpiCaseTicket ticket : existing.values()) {
                if (ticket.isPresent() && !seen.contains(ticket.getSourceItemId())) {
                    ticket.setPresent(false);
                    ticket.setLastSyncedAt(now);
                    toSave.add(ticket);
                    markedAbsent++;
                }
            }
        }

        ticketRepository.saveAll(toSave);
        log.info("Board {} ({}): {} inserted, {} updated, {} unchanged, {} marked absent",
                board.getId(), board.getServiceLine(), inserted, updated, unchanged, markedAbsent);
        return new WriteResult(inserted, updated, unchanged, markedAbsent);
    }
}
