package com.raaspal.robotrecommendation.ai.service;

import com.raaspal.robotrecommendation.ai.dto.AiProposalRequest;
import com.raaspal.robotrecommendation.ai.dto.AiProposalResult;
import com.raaspal.robotrecommendation.proposal.dto.SlideManifest;

public interface ProposalGenerationAiService {

    AiProposalResult generateProposal(AiProposalRequest request);

    /** Convert proposal markdown into a structured slide manifest for PPTX export.
     *  Returns null when AI is unavailable; callers must handle the fallback. */
    default SlideManifest generateSlideManifest(String proposalContent) {
        return null;
    }
}
