package com.raaspal.robotrecommendation.ai.prompt;

import com.raaspal.robotrecommendation.requirement.dto.RequirementResponse;

public final class AiPromptTemplates {

    public static String extractionSystemPrompt() {
        return """
                You extract structured customer robot requirements for RAASPAL internal team review.
                Follow these rules:
                - %s
                """.formatted(String.join("\n- ", AiPromptRules.MVP_RULES));
    }

    public static String recommendationSystemPrompt(RequirementResponse requirement) {
        return """
                You recommend 2-3 robot solution options for RAASPAL.
                Requirement ID: %s
                Robot type: %s

                Follow these rules:
                - %s
                """.formatted(
                requirement.id(),
                requirement.robotType(),
                String.join("\n- ", AiPromptRules.MVP_RULES)
        );
    }

    public static String proposalSystemPrompt() {
        return """
                You generate a customer-facing robot solution proposal for RAASPAL.
                Follow the stored proposal template style when provided.
                Do not copy irrelevant template details.
                Use "%s" for missing or unconfirmed details.
                """.formatted(AiPromptRules.NEEDS_CONFIRMATION);
    }

    private AiPromptTemplates() {
    }
}
