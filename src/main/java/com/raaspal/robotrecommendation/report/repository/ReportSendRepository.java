package com.raaspal.robotrecommendation.report.repository;

import com.raaspal.robotrecommendation.report.entity.ReportSend;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface ReportSendRepository extends JpaRepository<ReportSend, UUID> {

    /** Used by the scheduler to check idempotency — skip if already SENT. */
    Optional<ReportSend> findByCustomerProfileIdAndReportMonthAndStatus(
            UUID customerProfileId, String reportMonth, ReportSend.Status status);

    /** History view: all sends for a customer, newest first. */
    List<ReportSend> findByCustomerProfileIdOrderBySentAtDesc(UUID customerProfileId);

    /** History view: all sends for a month (for debugging a run). */
    List<ReportSend> findByReportMonthOrderBySentAtDesc(String reportMonth);
}
