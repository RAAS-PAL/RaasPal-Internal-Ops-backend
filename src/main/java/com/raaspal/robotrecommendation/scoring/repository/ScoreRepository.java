package com.raaspal.robotrecommendation.scoring.repository;

import com.raaspal.robotrecommendation.scoring.entity.Score;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.UUID;

public interface ScoreRepository extends JpaRepository<Score, UUID> {

    List<Score> findByRecommendationItemId(UUID recommendationItemId);
}