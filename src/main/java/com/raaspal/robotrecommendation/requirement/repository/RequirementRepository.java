package com.raaspal.robotrecommendation.requirement.repository;

import com.raaspal.robotrecommendation.common.enums.RequirementStatus;
import com.raaspal.robotrecommendation.common.enums.RobotType;
import com.raaspal.robotrecommendation.requirement.entity.Requirement;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.UUID;

public interface RequirementRepository extends JpaRepository<Requirement, UUID> {

    Page<Requirement> findByCustomerProfileId(UUID customerProfileId, Pageable pageable);

    Page<Requirement> findByStatus(RequirementStatus status, Pageable pageable);

    Page<Requirement> findByCreatedById(UUID createdById, Pageable pageable);

    Page<Requirement> findByCustomerProfileIdAndStatus(
            UUID customerProfileId, RequirementStatus status, Pageable pageable);

    Page<Requirement> findByRobotType(RobotType robotType, Pageable pageable);
}
