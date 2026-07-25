package com.raaspal.robotrecommendation.telemetry.repository;

import com.raaspal.robotrecommendation.telemetry.entity.RobotTaskReport;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.UUID;

@Repository
public interface RobotTaskReportRepository extends JpaRepository<RobotTaskReport, UUID> {

    boolean existsByExternalTaskId(String externalTaskId);

    /**
     * Which of the given external task ids are already stored — one query for a
     * whole fetched batch, so a sync does not run an exists-check per report.
     */
    @Query("select r.externalTaskId from RobotTaskReport r where r.externalTaskId in :ids")
    List<String> findExistingExternalTaskIds(@Param("ids") Collection<String> ids);

    List<RobotTaskReport> findByCustomerProfileIdAndReportMonth(UUID customerProfileId, String reportMonth);

    List<RobotTaskReport> findByRobotUnitIdAndReportMonth(UUID robotUnitId, String reportMonth);

    List<RobotTaskReport> findByReportMonth(String reportMonth);

    /** Paged task reports for one robot, most recent first (partner API). */
    Page<RobotTaskReport> findByRobotUnitIdOrderByStartTimeDesc(UUID robotUnitId, Pageable pageable);

    /** Paged task reports for one robot in a given month (YYYY-MM), most recent first (partner API). */
    Page<RobotTaskReport> findByRobotUnitIdAndReportMonthOrderByStartTimeDesc(
            UUID robotUnitId, String reportMonth, Pageable pageable);

    /**
     * Paged task reports for one robot whose start time falls within
     * {@code [startTimeMin, startTimeMax]}, most recent first — the partner API's
     * day/range filter.
     */
    Page<RobotTaskReport> findByRobotUnitIdAndStartTimeBetweenOrderByStartTimeDesc(
            UUID robotUnitId, Instant startTimeMin, Instant startTimeMax, Pageable pageable);
}