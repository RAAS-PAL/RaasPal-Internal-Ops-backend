package com.raaspal.robotrecommendation.casereport.repository;

import com.raaspal.robotrecommendation.casereport.entity.CaseReportRun;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
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

    /**
     * The same run, row-locked until the transaction ends. Every read-modify-write of a
     * run's rows goes through this — an edit, an added or removed row, and a regeneration
     * storing its result — so two of them cannot each read the old rows and have the
     * later save silently drop the other's change. Since the sheets refresh themselves
     * every few minutes, that overlap is routine rather than rare.
     */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT r FROM CaseReportRun r WHERE r.definitionId = :definitionId AND r.runDate = :runDate")
    Optional<CaseReportRun> findForUpdate(@Param("definitionId") UUID definitionId,
                                          @Param("runDate") LocalDate runDate);

    /** Recent runs first, for a history list. */
    List<CaseReportRun> findTop30ByDefinitionIdOrderByRunDateDesc(UUID definitionId);
}
