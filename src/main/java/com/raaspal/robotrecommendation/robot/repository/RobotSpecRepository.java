package com.raaspal.robotrecommendation.robot.repository;

import com.raaspal.robotrecommendation.common.enums.Environment;
import com.raaspal.robotrecommendation.common.enums.PricingType;
import com.raaspal.robotrecommendation.robot.entity.RobotSpec;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.math.BigDecimal;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Repository
public interface RobotSpecRepository extends JpaRepository<RobotSpec, UUID> {

    Optional<RobotSpec> findByRobot_Id(UUID robotId);

    boolean existsByRobot_Id(UUID robotId);

    // Used by the hard-filter engine to eliminate unqualified robots before AI scoring
    @Query("SELECT s FROM RobotSpec s WHERE s.robot.isActive = true " +
           "AND (s.environment = :environment OR s.environment IS NULL) " +
           "AND (s.pricingType = :pricingType OR s.pricingType = 'BOTH') " +
           "AND (:maxBudget IS NULL OR " +
                "(:pricingType = 'SALE'   AND s.salePriceThb   <= :maxBudget) OR " +
                "(:pricingType = 'RENTAL' AND s.rentalPriceThb <= :maxBudget)) " +
           "AND (:minCleaningWidth IS NULL OR s.cleaningWidthMm >= :minCleaningWidth) " +
           "AND (:minWorkTime IS NULL OR s.batteryWorkTimeH >= :minWorkTime)")
    List<RobotSpec> findQualifiedRobots(
            @Param("environment") Environment environment,
            @Param("pricingType") PricingType pricingType,
            @Param("maxBudget") BigDecimal maxBudget,
            @Param("minCleaningWidth") Integer minCleaningWidth,
            @Param("minWorkTime") BigDecimal minWorkTime
    );
}