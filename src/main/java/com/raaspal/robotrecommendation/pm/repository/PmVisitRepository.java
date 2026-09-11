package com.raaspal.robotrecommendation.pm.repository;

import com.raaspal.robotrecommendation.pm.entity.PmVisit;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

@Repository
public interface PmVisitRepository extends JpaRepository<PmVisit, UUID> {

    List<PmVisit> findBySourceBoardId(String sourceBoardId);

    /** See {@code PmContractRepository.markAbsentBefore} for why this is stamp-based. */
    @Modifying
    @Query("""
            UPDATE PmVisit v
               SET v.isPresent = false
             WHERE v.sourceBoardId = :boardId
               AND v.isPresent = true
               AND v.lastSyncedAt < :runStart
            """)
    int markAbsentBefore(@Param("boardId") String boardId, @Param("runStart") OffsetDateTime runStart);

    /**
     * Visits due in a date range, one row each, for the month view.
     *
     * <p>{@code planDate} may be null only when {@code includeUndated} is true: the
     * unscheduled PM that the range cannot match by definition, surfaced on purpose
     * rather than dropped.
     */
    @Query(value = """
            SELECT v.id                        AS visitId,
                   v.visit_name                AS visitName,
                   v.pm_sequence               AS pmSequence,
                   v.plan_date                 AS planDate,
                   v.action_date               AS actionDate,
                   v.time_text                 AS timeText,
                   v.status_raw                AS statusRaw,
                   v.status_bucket             AS statusBucket,
                   v.owner_names               AS ownerNames,
                   c.id                        AS contractId,
                   c.item_name                 AS itemName,
                   c.customer_name_raw         AS customerName,
                   c.project_raw               AS project,
                   c.service_line              AS serviceLine,
                   c.company                   AS company,
                   c.province_resolved         AS province,
                   c.region_resolved           AS region,
                   c.zone_resolved             AS zone,
                   c.robot_model               AS robotModel,
                   c.robot_count               AS robotCount,
                   c.contract_type             AS contractType
              FROM pm_visit v
              JOIN pm_contract c ON c.id = v.pm_contract_id
             WHERE v.is_present = TRUE
               AND c.is_present = TRUE
               AND ( (v.plan_date BETWEEN :from AND :to)
                     OR (CAST(:includeUndated AS boolean) = TRUE AND v.plan_date IS NULL) )
               AND (CAST(:serviceLine AS text) IS NULL OR c.service_line = CAST(:serviceLine AS text))
               AND (CAST(:region AS text)      IS NULL OR c.region_resolved = CAST(:region AS text))
               AND (CAST(:zone AS text)        IS NULL OR c.zone_resolved = CAST(:zone AS text))
               AND (CAST(:province AS text)    IS NULL OR c.province_resolved = CAST(:province AS text))
               AND (CAST(:status AS text)      IS NULL OR v.status_bucket = CAST(:status AS text))
               AND (CAST(:owner AS text)       IS NULL OR v.owner_names ILIKE CONCAT('%', CAST(:owner AS text), '%'))
               AND (CAST(:q AS text)           IS NULL OR c.item_name ILIKE CONCAT('%', CAST(:q AS text), '%')
                                                       OR c.customer_name_raw ILIKE CONCAT('%', CAST(:q AS text), '%')
                                                       OR c.project_raw ILIKE CONCAT('%', CAST(:q AS text), '%'))
             ORDER BY v.plan_date NULLS LAST, c.region_resolved, c.province_resolved, c.item_name
            """, nativeQuery = true)
    List<VisitRow> findVisitsInRange(@Param("from") LocalDate from,
                                     @Param("to") LocalDate to,
                                     @Param("includeUndated") boolean includeUndated,
                                     @Param("serviceLine") String serviceLine,
                                     @Param("region") String region,
                                     @Param("zone") String zone,
                                     @Param("province") String province,
                                     @Param("status") String status,
                                     @Param("owner") String owner,
                                     @Param("q") String q);

    /** One visit as the month view lists it. */
    interface VisitRow {
        UUID getVisitId();
        String getVisitName();
        Integer getPmSequence();
        LocalDate getPlanDate();
        LocalDate getActionDate();
        String getTimeText();
        String getStatusRaw();
        String getStatusBucket();
        String getOwnerNames();
        UUID getContractId();
        String getItemName();
        String getCustomerName();
        String getProject();
        String getServiceLine();
        String getCompany();
        String getProvince();
        String getRegion();
        String getZone();
        String getRobotModel();
        Integer getRobotCount();
        String getContractType();
    }

    /**
     * Every chain with how many sites it has, biggest first.
     *
     * <p>Ordered by size rather than alphabetically because the filter exists to
     * take big chains out of the way, and the ones worth excluding are exactly the
     * ones at the top.
     */
    @Query("""
            SELECT c.company AS company, COUNT(c) AS siteCount
              FROM PmContract c
             WHERE c.isPresent = true
               AND c.company IS NOT NULL
             GROUP BY c.company
             ORDER BY COUNT(c) DESC, c.company ASC
            """)
    List<CompanyOption> findCompanyOptions();

    /** One entry in the company filter. */
    interface CompanyOption {
        String getCompany();
        long getSiteCount();
    }

    /** Count of visits with no plan date at all - the unscheduled backlog. */
    @Query(value = """
            SELECT COUNT(*)
              FROM pm_visit v
              JOIN pm_contract c ON c.id = v.pm_contract_id
             WHERE v.is_present = TRUE
               AND c.is_present = TRUE
               AND v.plan_date IS NULL
               AND v.status_bucket <> 'COMPLETED'
               AND (CAST(:serviceLine AS text) IS NULL OR c.service_line = CAST(:serviceLine AS text))
            """, nativeQuery = true)
    long countUndated(@Param("serviceLine") String serviceLine);

    /**
     * The distinct contents of the owner column.
     *
     * <p>Returned unsplit, and separated into individual engineers by the caller. A
     * visit can name two, and splitting in SQL means UNNEST(STRING_TO_ARRAY(...)),
     * which is Postgres-only and cannot run against the H2 database the tests use.
     * There are only a few dozen distinct values, so the split is free in Java.
     */
    @Query("""
            SELECT DISTINCT v.ownerNames
              FROM PmVisit v
             WHERE v.isPresent = true
               AND v.ownerNames IS NOT NULL
            """)
    List<String> findDistinctOwnerNames();
}
