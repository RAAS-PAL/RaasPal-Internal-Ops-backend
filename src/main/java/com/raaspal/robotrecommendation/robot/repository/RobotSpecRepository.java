package com.raaspal.robotrecommendation.robot.repository;

import com.raaspal.robotrecommendation.robot.entity.RobotSpec;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Repository
public interface RobotSpecRepository extends JpaRepository<RobotSpec, UUID> {

    Optional<RobotSpec> findByRobot_Id(UUID robotId);

    boolean existsByRobot_Id(UUID robotId);

    /**
     * Every spec row for a page of robots, in one query.
     *
     * <p>The catalogue list used to ask for each robot's specs on its own, so rendering it
     * cost one round trip per row. That was tolerable at nineteen models and is not at
     * seventy: each query is fast, and what the reader waits for is the latency to the
     * database paid seventy times over.
     */
    List<RobotSpec> findByRobot_IdIn(Collection<UUID> robotIds);
}
