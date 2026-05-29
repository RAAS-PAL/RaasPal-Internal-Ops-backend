package com.raaspal.robotrecommendation.ai.service;

import com.raaspal.robotrecommendation.ai.dto.AiProposalRequest;
import com.raaspal.robotrecommendation.ai.dto.AiProposalResult;

public interface ProposalGenerationAiService {

    AiProposalResult generateProposal(AiProposalRequest request);
}
