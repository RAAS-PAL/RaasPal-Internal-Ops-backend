package com.raaspal.robotrecommendation.robotunit.repository;

import com.raaspal.robotrecommendation.robotunit.entity.RobotUnit;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.Optional;
import java.util.UUID;

@Repository
public interface RobotUnitRepository extends JpaRepository<RobotUnit, UUID> {

    Optional<RobotUnit> findBySerialNumber(String serialNumber);
}