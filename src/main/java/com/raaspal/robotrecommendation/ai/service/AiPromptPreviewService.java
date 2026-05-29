package com.raaspal.robotrecommendation.ai.service;

import com.raaspal.robotrecommendation.ai.dto.AiPromptPreview;
import com.raaspal.robotrecommendation.ai.prompt.AiPromptRules;
import com.raaspal.robotrecommendation.ai.prompt.AiPromptTemplates;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

@Service
public class AiPromptPreviewService {

    @Value("${app.ai.provider:mock}")
    private String aiProvider;

    public AiPromptPreview getPromptPreview() {
        return new AiPromptPreview(
                aiProvider,
                AiPromptRules.MVP_RULES,
                AiPromptTemplates.extractionSystemPrompt(),
                AiPromptTemplates.proposalSystemPrompt()
        );
    }
}
