package com.raaspal.robotrecommendation.robotunit.repository;

import com.raaspal.robotrecommendation.robotunit.entity.Deployment;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.UUID;

@Repository
public interface DeploymentRepository extends JpaRepository<Deployment, UUID> {

    List<Deployment> findByIsActiveTrue();

    List<Deployment> findByRobotUnitIdAndIsActiveTrue(UUID robotUnitId);

    List<Deployment> findByCustomerProfileIdAndIsActiveTrue(UUID customerProfileId);
}