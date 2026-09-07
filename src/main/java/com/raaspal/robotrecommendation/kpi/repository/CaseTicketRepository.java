package com.raaspal.robotrecommendation.kpi.repository;

import com.raaspal.robotrecommendation.kpi.entity.CaseTicket;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.stereotype.Repository;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Repository
public interface CaseTicketRepository extends JpaRepository<CaseTicket, UUID> {

    /** Every row ever synced from one board, present or not — the sync merges against this. */
    List<CaseTicket> findAllBySourceAndSourceBoardId(String source, String sourceBoardId);

    /** Tickets opened in a window, excluding ones the board no longer returns. */
    List<CaseTicket> findAllByPresentTrueAndOpenDateBetween(LocalDate from, LocalDate to);

    long countByPresentTrue();

    /** When any ticket was last refreshed — the "data as of" stamp on the dashboard. */
    @Query("select max(t.lastSyncedAt) from CaseTicket t")
    Optional<LocalDateTime> findLastSyncedAt();
}
