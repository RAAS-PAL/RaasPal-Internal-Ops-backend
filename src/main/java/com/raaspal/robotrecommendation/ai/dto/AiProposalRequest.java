package com.raaspal.robotrecommendation.ai.dto;

import com.raaspal.robotrecommendation.proposal.dto.ProposalTemplateResponse;
import com.raaspal.robotrecommendation.recommendation.dto.RecommendationItemResponse;
import com.raaspal.robotrecommendation.requirement.dto.RequirementResponse;

public record AiProposalRequest(
        RequirementResponse requirement,
        RecommendationItemResponse selectedOption,
        ProposalTemplateResponse template
) {
}
