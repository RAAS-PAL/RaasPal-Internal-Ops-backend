package com.raaspal.robotrecommendation.report.repository;

import com.raaspal.robotrecommendation.report.entity.ReportSend;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface ReportSendRepository extends JpaRepository<ReportSend, UUID> {

    /**
     * The monthly run's idempotency check: skip a customer already SENT. Keyed on
     * kind so that only a BUNDLE counts — a single robot's report sent by hand from
     * the preview tab is recorded here too, but must not silence the run.
     */
    Optional<ReportSend> findByCustomerProfileIdAndReportMonthAndKindAndStatus(
            UUID customerProfileId, String reportMonth, ReportSend.Kind kind, ReportSend.Status status);

    /** History view: all sends for a customer, newest first. */
    List<ReportSend> findByCustomerProfileIdOrderBySentAtDesc(UUID customerProfileId);

    /** History view: all sends for a month (for debugging a run). */
    List<ReportSend> findByReportMonthOrderBySentAtDesc(String reportMonth);
}
