package com.raaspal.robotrecommendation.ai.dto;

import java.util.List;

public record AiPromptPreview(
        String provider,
        List<String> rules,
        String extractionPrompt,
        String proposalPrompt
) {
}
