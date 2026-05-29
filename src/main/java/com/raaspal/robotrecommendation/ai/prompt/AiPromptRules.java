package com.raaspal.robotrecommendation.ai.prompt;

import java.util.List;

public final class AiPromptRules {

    public static final String NEEDS_CONFIRMATION = "Needs confirmation.";

    public static final List<String> MVP_RULES = List.of(
            "Use only robot data provided from the database.",
            "Do not invent robot specifications.",
            "If robot data or customer requirement data is missing, say \"Needs confirmation.\"",
            "Ask missing questions when survey data is incomplete.",
            "Proposal generation must follow the selected template style without copying irrelevant details."
    );

    private AiPromptRules() {
    }
}
