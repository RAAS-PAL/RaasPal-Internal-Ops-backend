package com.raaspal.robotrecommendation.casereport.service;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import com.raaspal.robotrecommendation.ai.service.CasePartsAiService;
import com.raaspal.robotrecommendation.casereport.adapters.monday.dto.MondayItem;
import com.raaspal.robotrecommendation.casereport.dto.CasePartsSummary;
import com.raaspal.robotrecommendation.casereport.dto.CaseProgressRequest;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.time.LocalDate;
import java.util.List;

/**
 * The four part-tracking cells of a RAW_AOTGA row.
 *
 * <p>The counterpart of {@link SolutionLineWriter}, with the same rule: a value somebody
 * typed into the board wins, field by field, and the comment thread goes to the model
 * for whatever is left. The board's parts columns exist and are empty on every airport
 * ticket today, so in practice the model writes all four — but the typed path is kept,
 * because the day the team starts filling those columns their value should win without
 * a deploy.
 *
 * <p>The thread is sent once, and only when at least one field is still blank after the
 * typed values are read. Four fields from one call, not four calls.
 */
@Service
@RequiredArgsConstructor
public class PartsLineWriter {

    private final CasePartsAiService partsAi;

    /**
     * The model's answers, by the exact thread and context it was given.
     *
     * <p>The sheets regenerate every few minutes through the day, and between two runs
     * almost no ticket gets a new comment. {@link CaseProgressRequest} is a record of
     * records, so it is equal exactly when the thread, the statuses, the site and the
     * report date are — a new comment or a status change is a new key and is asked
     * again; an unchanged ticket costs nothing. The report date is part of the key, so
     * every ticket is summarised afresh once each morning. Bounded and expiring, so a
     * long-running server does not accumulate old threads. In memory: a restart simply
     * re-asks once. A failed (null) answer is never kept.
     */
    private final Cache<CaseProgressRequest, CasePartsSummary> reads = Caffeine.newBuilder()
            .maximumSize(5_000)
            .expireAfterWrite(Duration.ofHours(36))
            .build();

    /**
     * @param typedPart     the board's Spare Parts Name cell, used as-is when not blank
     * @param typedWaiting  the board's current-update cell
     * @param typedFrom     the board's waiting-from cell
     * @param typedReceived the board's part-received date, already parsed
     * @param site          the row's site label, context for the model
     * @param problem       the Main Issue cell
     * @param status        the Status cell
     * @param supStatus     the Sup Status cell
     * @return every field either typed, read from the thread, or null — never a null
     *         summary
     */
    public CasePartsSummary write(MondayItem item,
                                  String typedPart,
                                  String typedWaiting,
                                  String typedFrom,
                                  LocalDate typedReceived,
                                  String site,
                                  String problem,
                                  String status,
                                  String supStatus,
                                  LocalDate asOf) {
        CasePartsSummary typed = new CasePartsSummary(
                blankToNull(typedPart), blankToNull(typedWaiting), blankToNull(typedFrom), typedReceived);

        boolean complete = typed.requiredPart() != null && typed.waiting() != null
                && typed.waitingFrom() != null && typed.partReceived() != null;
        if (complete) {
            return typed;
        }

        List<CaseProgressRequest.Comment> comments = SolutionLineWriter.commentsOf(item);
        if (comments.isEmpty()) {
            return typed;
        }

        CaseProgressRequest request = new CaseProgressRequest(site, problem, status, supStatus, asOf, comments);
        CasePartsSummary read = reads.getIfPresent(request);
        if (read == null) {
            read = partsAi.extractParts(request);
            if (read != null) {
                reads.put(request, read);
            }
        }
        if (read == null) {
            read = CasePartsSummary.EMPTY;
        }

        return new CasePartsSummary(
                typed.requiredPart() != null ? typed.requiredPart() : read.requiredPart(),
                typed.waiting() != null ? typed.waiting() : read.waiting(),
                typed.waitingFrom() != null ? typed.waitingFrom() : read.waitingFrom(),
                typed.partReceived() != null ? typed.partReceived() : read.partReceived());
    }

    private static String blankToNull(String s) {
        return s == null || s.isBlank() ? null : s;
    }
}
