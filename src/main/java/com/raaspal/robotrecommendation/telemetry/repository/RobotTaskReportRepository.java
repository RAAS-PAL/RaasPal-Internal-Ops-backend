package com.raaspal.robotrecommendation.telemetry.repository;

import com.raaspal.robotrecommendation.telemetry.entity.RobotTaskReport;
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
}