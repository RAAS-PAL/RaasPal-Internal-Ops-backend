package com.raaspal.robotrecommendation.casereport.repository;

import com.raaspal.robotrecommendation.casereport.entity.CaseReportRun;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Repository
public interface CaseReportRunRepository extends JpaRepository<CaseReportRun, UUID> {

    /**
     * The one run for a report on a date. Matches uq_case_report_run_day, so this is a
     * unique lookup rather than a "first of many".
     */
    Optional<CaseReportRun> findByDefinitionIdAndRunDate(UUID definitionId, LocalDate runDate);

    /** Recent runs first, for a history list. */
    List<CaseReportRun> findTop30ByDefinitionIdOrderByRunDateDesc(UUID definitionId);
}
