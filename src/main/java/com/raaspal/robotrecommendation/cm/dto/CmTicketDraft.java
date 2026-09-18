package com.raaspal.robotrecommendation.cm.dto;

import com.raaspal.robotrecommendation.ai.dto.CmReportDraft;

/**
 * A CM report drafted from a monday ticket: the text the draft was extracted from
 * (columns and the comment thread, so the staff can see where each field came
 * from) and the draft itself, with the facts the ticket states outright - ticket
 * number, serial, model, the technician - taken from the ticket, not the model.
 */
public record CmTicketDraft(CmTicketSummary ticket, String sourceText, CmReportDraft draft) {
}
