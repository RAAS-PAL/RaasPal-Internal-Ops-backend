package com.raaspal.robotrecommendation.robotunit.repository;

import com.raaspal.robotrecommendation.robotunit.entity.RobotUnit;
import com.raaspal.robotrecommendation.robotunit.entity.RobotUnitStatus;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Repository
public interface RobotUnitRepository extends JpaRepository<RobotUnit, UUID> {

    Optional<RobotUnit> findBySerialNumber(String serialNumber);

    boolean existsBySerialNumber(String serialNumber);

    /* ─── Stock (RIMS) ────────────────────────────────────────────────────── */

    long countByStatus(RobotUnitStatus status);

    List<RobotUnit> findByStatusOrderByBrandAscModelAscSerialNumberAsc(RobotUnitStatus status);

    List<RobotUnit> findByStatusInOrderByBrandAscModelAscSerialNumberAsc(List<RobotUnitStatus> statuses);

    long countByStatusIn(List<RobotUnitStatus> statuses);

    /**
     * Stock grouped by catalogue model — what RIMS shows as "3 x Phantas v1.3".
     *
     * <p>Grouped on {@code robot_id} rather than the free-text model column, because
     * the fleet records the same machine several ways ("Phantas", "Phantas V1.1")
     * and counting by text would split one model across several rows. Units with no
     * catalogue link are excluded here and reported separately — 53 of 152 are
     * currently unmatched, and folding them into a model would be a guess.
     */
    @Query("""
           SELECT u.robotId, COUNT(u)
           FROM RobotUnit u
           WHERE u.status = :status AND u.robotId IS NOT NULL
           GROUP BY u.robotId
           """)
    List<Object[]> countByRobotIdAndStatus(@Param("status") RobotUnitStatus status);

    long countByStatusAndRobotIdIsNull(RobotUnitStatus status);
}