package com.raaspal.robotrecommendation.ai.service;

import com.raaspal.robotrecommendation.ai.dto.AiRecommendationResult;
import com.raaspal.robotrecommendation.ai.dto.RobotCatalogData;
import com.raaspal.robotrecommendation.requirement.dto.RequirementResponse;

import java.util.List;

public interface RobotRecommendationAiService {

    AiRecommendationResult recommend(
            RequirementResponse requirement,
            List<RobotCatalogData> robotCatalog,
            int optionCount
    );
}
