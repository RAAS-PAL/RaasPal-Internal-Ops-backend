package com.raaspal.robotrecommendation.telemetry.repository;

import com.raaspal.robotrecommendation.telemetry.entity.RobotTaskReport;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.UUID;

@Repository
public interface RobotTaskReportRepository extends JpaRepository<RobotTaskReport, UUID> {

    boolean existsByExternalTaskId(String externalTaskId);

    List<RobotTaskReport> findByCustomerProfileIdAndReportMonth(UUID customerProfileId, String reportMonth);

    List<RobotTaskReport> findByRobotUnitIdAndReportMonth(UUID robotUnitId, String reportMonth);

    List<RobotTaskReport> findByReportMonth(String reportMonth);

    /** Paged task reports for one robot, most recent first (partner API). */
    Page<RobotTaskReport> findByRobotUnitIdOrderByStartTimeDesc(UUID robotUnitId, Pageable pageable);

    /** Paged task reports for one robot in a given month (YYYY-MM), most recent first (partner API). */
    Page<RobotTaskReport> findByRobotUnitIdAndReportMonthOrderByStartTimeDesc(
            UUID robotUnitId, String reportMonth, Pageable pageable);
}