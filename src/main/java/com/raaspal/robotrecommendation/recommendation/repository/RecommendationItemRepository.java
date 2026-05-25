package com.raaspal.robotrecommendation.recommendation.repository;

import com.raaspal.robotrecommendation.recommendation.entity.RecommendationItem;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.UUID;

public interface RecommendationItemRepository extends JpaRepository<RecommendationItem, UUID> {

    List<RecommendationItem> findByRecommendationIdOrderByRankPositionAsc(UUID recommendationId);
}