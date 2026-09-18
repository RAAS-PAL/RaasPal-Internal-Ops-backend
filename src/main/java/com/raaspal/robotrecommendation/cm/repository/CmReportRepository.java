package com.raaspal.robotrecommendation.cm.repository;

import com.raaspal.robotrecommendation.cm.entity.CmReport;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.Collection;
import java.util.List;
import java.util.UUID;

@Repository
public interface CmReportRepository extends JpaRepository<CmReport, UUID> {

    List<CmReport> findAllByOrderByCreatedAtDesc();

    /**
     * Free-text search over the three identifiers staff actually remember a past
     * report by: the Monday ticket number, the customer, and the robot's serial.
     * Ordered newest-first to match the unfiltered history list.
     */
    @Query("SELECT r FROM CmReport r WHERE "
            + "LOWER(r.ticketNo) LIKE LOWER(CONCAT('%', :keyword, '%')) OR "
            + "LOWER(r.customerName) LIKE LOWER(CONCAT('%', :keyword, '%')) OR "
            + "LOWER(r.serialNumber) LIKE LOWER(CONCAT('%', :keyword, '%')) "
            + "ORDER BY r.createdAt DESC")
    List<CmReport> search(@Param("keyword") String keyword);

    /** Of these ticket numbers, the ones a report has already been written for. */
    @Query("SELECT DISTINCT r.ticketNo FROM CmReport r WHERE r.ticketNo IN :ticketNos")
    List<String> findExistingTicketNos(@Param("ticketNos") Collection<String> ticketNos);
}
