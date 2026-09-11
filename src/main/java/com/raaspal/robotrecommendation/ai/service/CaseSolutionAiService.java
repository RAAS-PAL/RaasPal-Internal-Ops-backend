package com.raaspal.robotrecommendation.ai.service;

import com.raaspal.robotrecommendation.casereport.dto.CaseProgressRequest;

/**
 * Writes the Solution line of a pending-case report from a ticket's comment thread.
 *
 * <p>Implemented by both {@link ClaudeAiService} and {@link MockAiService}. The two are
 * paired by {@code @ConditionalOnExpression} on the API key so exactly one exists, and
 * adding a method to one but not the other breaks the context in whichever mode lacks it.
 *
 * <p>Never throws. A report with an empty Solution cell is reviewable; a report that
 * failed to generate because one thread confused the model is not.
 */
public interface CaseSolutionAiService {

    /**
     * @return the dated log in the report's style, or an empty string when there is
     *         nothing to say — never null
     */
    String summariseProgress(CaseProgressRequest request);
}
