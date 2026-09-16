package com.raaspal.robotrecommendation.casereport.repository;

import com.raaspal.robotrecommendation.casereport.entity.CaseTicketStatusHistory;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Repository
public interface CaseTicketStatusHistoryRepository
        extends JpaRepository<CaseTicketStatusHistory, UUID> {

    /** How many days of history exist for a given business date. */
    long countByObservedOn(java.time.LocalDate observedOn);

    /**
     * The status log for one ticket, oldest first — the order the delivery report's
     * Solution column prints in.
     */
    List<CaseTicketStatusHistory> findByCaseTicketIdOrderByObservedOnAsc(UUID caseTicketId);

    /**
     * The last recorded status, which is what a new observation is compared against.
     *
     * <p>A row is only written when the status actually changed, so this is the whole
     * of the "did anything change" check.
     */
    Optional<CaseTicketStatusHistory> findFirstByCaseTicketIdOrderByObservedOnDescObservedAtDesc(
            UUID caseTicketId);

    /**
     * Today's row, if one exists.
     *
     * <p>Read before writing so a second sync on the same day updates that row rather
     * than tripping {@code uq_case_ticket_status_day}. A status that flips twice in an
     * afternoon should leave one line under today's date carrying the latest value —
     * not two lines, and not a failed run.
     */
    Optional<CaseTicketStatusHistory> findByCaseTicketIdAndObservedOn(UUID caseTicketId,
                                                                     LocalDate observedOn);
}
