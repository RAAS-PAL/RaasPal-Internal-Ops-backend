package com.raaspal.robotrecommendation.ai.service;

import com.raaspal.robotrecommendation.ai.dto.CmReportDraft;

/**
 * Pulls the structured Corrective Maintenance report fields out of a raw ticket
 * pasted from Monday.com.
 * <p>
 * Implemented by both {@link ClaudeAiService} and {@link MockAiService} — they are
 * paired by {@code @ConditionalOnExpression} on the API key, so exactly one bean
 * exists and adding this interface to only one of them breaks the application
 * context in whichever mode lacks it.
 */
public interface CmReportExtractionService {

    /** Never throws: on any AI or parse failure it degrades to a mostly-empty draft. */
    CmReportDraft extractCmReport(String sourceText);
}
