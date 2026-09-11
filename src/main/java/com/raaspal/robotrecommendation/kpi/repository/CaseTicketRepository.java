package com.raaspal.robotrecommendation.kpi.repository;

import com.raaspal.robotrecommendation.kpi.entity.CaseTicket;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
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

    /**
     * Every live ticket whose KPI date falls in the window — CMs by open date,
     * installations by the TimeLine end. One query rather than two so the
     * follow-up matching sees both families in the same list.
     */
    @Query("select t from CaseTicket t where t.present = true and ("
            + "(t.openDate between :from and :to) or (t.installDate between :from and :to))")
    List<CaseTicket> findAllPresentInWindow(@Param("from") LocalDate from, @Param("to") LocalDate to);

    long countByPresentTrue();

    /**
     * Serial-to-service-line evidence, drawn from every CM ticket that has both.
     *
     * <p>The CM boards are single-line — Cleaning Tickets holds cleaning robots,
     * Delivery Tickets delivery ones — so a serial appearing on one of them is
     * proof of what that robot is. That is what lets an installation ticket be
     * classified even though its own board never says.
     *
     * <p>Deliberately not limited to the reporting window: a robot installed in
     * January may not be serviced until November, and the January figure should
     * still know what kind of robot it was.
     */
    @Query("select t.serialsNormalised, t.serviceLine from CaseTicket t "
            + "where t.present = true and t.ticketType = com.raaspal.robotrecommendation.kpi.entity.TicketType.CM "
            + "and t.serialsNormalised is not null and t.serviceLine is not null")
    List<Object[]> findSerialServiceLines();

    /** When any ticket was last refreshed — the "data as of" stamp on the dashboard. */
    @Query("select max(t.lastSyncedAt) from CaseTicket t")
    Optional<LocalDateTime> findLastSyncedAt();
}
