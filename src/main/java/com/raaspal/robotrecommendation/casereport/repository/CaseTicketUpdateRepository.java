package com.raaspal.robotrecommendation.casereport.repository;

import com.raaspal.robotrecommendation.casereport.entity.CaseTicketUpdate;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.UUID;

@Repository
public interface CaseTicketUpdateRepository extends JpaRepository<CaseTicketUpdate, UUID> {

    /** Newest first, which is the order a prompt wants and the order monday returns. */
    List<CaseTicketUpdate> findByCaseTicketIdOrderByPostedAtDesc(UUID caseTicketId);

    /** Whole threads for a set of tickets in one query, oldest first as a thread reads. */
    List<CaseTicketUpdate> findByCaseTicketIdInOrderByPostedAtAsc(java.util.Collection<UUID> caseTicketIds);

    /**
     * The update ids already stored for a ticket.
     *
     * <p>Read before inserting so the sync only writes comments it has not seen.
     * Comments are immutable once posted, so an id that is already present needs no
     * further work — and re-inserting would trip
     * {@code uq_case_ticket_update} and fail the whole run over a duplicate that
     * carries no new information.
     */
    @org.springframework.data.jpa.repository.Query("""
           SELECT u.sourceUpdateId FROM CaseTicketUpdate u
            WHERE u.caseTicketId = :ticketId
           """)
    List<String> findSourceUpdateIds(@org.springframework.data.repository.query.Param("ticketId") UUID ticketId);
}
