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
     * {@code [caseTicketId, sourceUpdateId]} for a set of tickets: what a sync chunk has
     * already stored, in one round trip instead of one per ticket.
     */
    @org.springframework.data.jpa.repository.Query("""
           SELECT u.caseTicketId, u.sourceUpdateId FROM CaseTicketUpdate u
            WHERE u.caseTicketId IN :ticketIds
           """)
    List<Object[]> findSourceUpdateIdsByTicketIds(
            @org.springframework.data.repository.query.Param("ticketIds") java.util.Collection<UUID> ticketIds);

    /**
     * Every comment id stored against one board's tickets. The brand sync compares
     * monday's newest comment ids with this to find threads that moved: a comment does
     * not always move the item's {@code updated_at}.
     */
    @org.springframework.data.jpa.repository.Query("""
           SELECT u.sourceUpdateId FROM CaseTicketUpdate u, CaseTicket t
            WHERE u.caseTicketId = t.id
              AND t.sourceBoardId = :boardId
           """)
    List<String> findSourceUpdateIdsOnBoard(
            @org.springframework.data.repository.query.Param("boardId") String boardId);

    /** {@code [caseTicketId, count]} for every ticket on a board that has comments. */
    @org.springframework.data.jpa.repository.Query("""
           SELECT u.caseTicketId, COUNT(u) FROM CaseTicketUpdate u, CaseTicket t
            WHERE u.caseTicketId = t.id
              AND t.sourceBoardId = :boardId
            GROUP BY u.caseTicketId
           """)
    List<Object[]> countByTicketOnBoard(
            @org.springframework.data.repository.query.Param("boardId") String boardId);

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
