package com.raaspal.robotrecommendation.robot.repository;

import com.raaspal.robotrecommendation.robot.entity.Robot;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.Optional;
import java.util.UUID;

@Repository
public interface RobotRepository extends JpaRepository<Robot, UUID> {

    Page<Robot> findAllByIsActiveTrue(Pageable pageable);

    Optional<Robot> findByIdAndIsActiveTrue(UUID id);

    Page<Robot> findAllByManufacturerIgnoreCaseAndIsActiveTrue(String manufacturer, Pageable pageable);

    @Query("SELECT r FROM Robot r WHERE r.isActive = true AND " +
           "(LOWER(r.model) LIKE LOWER(CONCAT('%', :keyword, '%')) OR " +
           "LOWER(r.manufacturer) LIKE LOWER(CONCAT('%', :keyword, '%')))")
    Page<Robot> search(@Param("keyword") String keyword, Pageable pageable);
}