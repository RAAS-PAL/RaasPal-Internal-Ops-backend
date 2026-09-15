package com.raaspal.robotrecommendation.telemetry.repository;

import com.raaspal.robotrecommendation.telemetry.entity.ZeroDataFollowup;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Repository
public interface ZeroDataFollowupRepository extends JpaRepository<ZeroDataFollowup, UUID> {

    List<ZeroDataFollowup> findByReportMonth(String reportMonth);

    Optional<ZeroDataFollowup> findByRobotUnitIdAndReportMonth(UUID robotUnitId, String reportMonth);
}
