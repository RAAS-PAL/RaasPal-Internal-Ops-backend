package com.raaspal.robotrecommendation.robot.repository;

import com.raaspal.robotrecommendation.robot.entity.RobotSpec;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.Optional;
import java.util.UUID;

@Repository
public interface RobotSpecRepository extends JpaRepository<RobotSpec, UUID> {

    Optional<RobotSpec> findByRobot_Id(UUID robotId);

    boolean existsByRobot_Id(UUID robotId);
}
