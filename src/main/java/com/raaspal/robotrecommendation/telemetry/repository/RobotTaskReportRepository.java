package com.raaspal.robotrecommendation.telemetry.repository;

import com.raaspal.robotrecommendation.telemetry.entity.RobotTaskReport;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

@Repository
public interface RobotTaskReportRepository extends JpaRepository<RobotTaskReport, UUID> {

    boolean existsByExternalTaskId(String externalTaskId);

    List<RobotTaskReport> findByCustomerProfileIdAndReportMonth(UUID customerProfileId, String reportMonth);

    List<RobotTaskReport> findByRobotUnitIdAndReportMonth(UUID robotUnitId, String reportMonth);

    List<RobotTaskReport> findByReportMonth(String reportMonth);

    /**
     * Loads a month's reports with {@code robotUnit} and {@code customerProfile}
     * eagerly fetched, so the monthly report service can read them with
     * {@code open-in-view=false} and the relations being {@code LAZY}.
     */
    @Query("select r from RobotTaskReport r "
            + "join fetch r.robotUnit "
            + "join fetch r.customerProfile "
            + "where r.reportMonth = :month")
    List<RobotTaskReport> findByReportMonthWithRefs(@Param("month") String month);

    /**
     * Loads reports whose {@code startTime} falls in {@code [start, end)} with
     * {@code robotUnit} and {@code customerProfile} eagerly fetched. Backs the
     * weekly (date-range) report run, which can't use the stored
     * {@code report_month} bucket.
     */
    @Query("select r from RobotTaskReport r "
            + "join fetch r.robotUnit "
            + "join fetch r.customerProfile "
            + "where r.startTime >= :start and r.startTime < :end")
    List<RobotTaskReport> findByStartTimeBetweenWithRefs(@Param("start") Instant start,
                                                         @Param("end") Instant end);
}