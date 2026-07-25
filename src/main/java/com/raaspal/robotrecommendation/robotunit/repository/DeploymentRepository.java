package com.raaspal.robotrecommendation.robotunit.repository;

import com.raaspal.robotrecommendation.robotunit.entity.Deployment;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.UUID;

@Repository
public interface DeploymentRepository extends JpaRepository<Deployment, UUID> {

    List<Deployment> findByIsActiveTrue();

    /**
     * All active deployments with their robot and customer eagerly fetched — one
     * query instead of an N+1 lazy load per row. Used to build the full robot
     * list without a per-robot deployment/customer query.
     */
    @Query("select d from Deployment d "
            + "join fetch d.robotUnit "
            + "join fetch d.customerProfile "
            + "where d.isActive = true")
    List<Deployment> findActiveWithRobotAndCustomer();

    List<Deployment> findByRobotUnitIdAndIsActiveTrue(UUID robotUnitId);

    List<Deployment> findByCustomerProfileIdAndIsActiveTrue(UUID customerProfileId);

    /** Active deployments serviced by a partner — the partner API's scoping query. */
    List<Deployment> findByPartnerIdAndIsActiveTrue(UUID partnerId);

    boolean existsByCustomerProfileId(UUID customerProfileId);

    long countByCustomerProfileIdAndIsActiveTrue(UUID customerProfileId);
}