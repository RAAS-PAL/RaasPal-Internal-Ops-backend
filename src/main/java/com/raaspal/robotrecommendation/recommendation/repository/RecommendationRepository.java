package com.raaspal.robotrecommendation.recommendation.repository;

import com.raaspal.robotrecommendation.common.enums.RecommendationStatus;
import com.raaspal.robotrecommendation.recommendation.entity.Recommendation;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.UUID;

public interface RecommendationRepository extends JpaRepository<Recommendation, UUID> {

    List<Recommendation> findByRequirementId(UUID requirementId);

    Page<Recommendation> findByStatus(RecommendationStatus status, Pageable pageable);
}