package com.raaspal.robotrecommendation.ai.service;

import com.raaspal.robotrecommendation.casereport.dto.CasePartsSummary;
import com.raaspal.robotrecommendation.casereport.dto.CaseProgressRequest;

/**
 * Reads the part-tracking cells of a RAW_AOTGA row out of a ticket's comment thread.
 *
 * <p>The sibling of {@link CaseSolutionAiService}, and given the same input — the thread
 * plus the ticket's status — because the parts story and the progress story are told in
 * the same comments. Implemented by both {@link ClaudeAiService} and {@link MockAiService};
 * the two are paired by {@code @ConditionalOnExpression} on the API key so exactly one
 * exists, and adding a method to one but not the other breaks the context in whichever
 * mode lacks it.
 *
 * <p>Never throws. A row with dashes in its parts columns is reviewable; a report that
 * failed to generate because one thread confused the model is not.
 */
public interface CasePartsAiService {

    /**
     * @return what the thread settles, with every unsettled field null;
     *         {@link CasePartsSummary#EMPTY} when it settles nothing — never null
     */
    CasePartsSummary extractParts(CaseProgressRequest request);
}
