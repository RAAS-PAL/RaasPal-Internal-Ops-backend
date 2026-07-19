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

    /** Active deployments serviced by a partner — the partner API's scoping query. */
    List<Deployment> findByPartnerIdAndIsActiveTrue(UUID partnerId);

    boolean existsByCustomerProfileId(UUID customerProfileId);

    long countByCustomerProfileIdAndIsActiveTrue(UUID customerProfileId);
}