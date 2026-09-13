package com.raaspal.robotrecommendation.casereport.service;

import com.raaspal.robotrecommendation.ai.service.CaseSolutionAiService;
import com.raaspal.robotrecommendation.casereport.adapters.monday.dto.MondayItem;
import com.raaspal.robotrecommendation.casereport.adapters.monday.dto.MondayUpdate;
import com.raaspal.robotrecommendation.casereport.dto.CaseProgressRequest;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;

/**
 * The Solution cell of a pending-case row, for every report that has one.
 *
 * <p>The board's own column wins when a human filled it. Otherwise the ticket's comment
 * thread goes to the model and comes back as the RE team's dated log. Shared by the
 * delivery and cleaning generators because the rule is the same on both boards; only
 * the column ids differ, and those stay with each generator.
 */
@Service
@RequiredArgsConstructor
public class SolutionLineWriter {

    /**
     * The zone a comment's date is taken in.
     *
     * <p>monday's timestamps are UTC, and the Solution line prints a date for each step.
     * Read as UTC, anything posted before 07:00 Bangkok would be dated the day before.
     */
    private static final ZoneId BUSINESS_ZONE = ZoneId.of("Asia/Bangkok");

    private final CaseSolutionAiService solutionAi;

    /**
     * @param typed     the board's Solution cell, used as-is when not blank
     * @param branch    the row's branch or site label, context for the model
     * @param problem   the Main Issue cell
     * @param status    the Status cell, so the model can finish with the current state
     * @param supStatus the Sup Status cell
     * @return the line, or null when there is nothing to say
     */
    public String write(MondayItem item,
                        String typed,
                        String branch,
                        String problem,
                        String status,
                        String supStatus,
                        LocalDate asOf) {
        if (typed != null && !typed.isBlank()) {
            return typed;
        }
        if (item.updates() == null || item.updates().isEmpty()) {
            return null;
        }

        List<CaseProgressRequest.Comment> comments = new ArrayList<>();
        // monday returns newest first; the line is written oldest first.
        for (int i = item.updates().size() - 1; i >= 0; i--) {
            MondayUpdate u = item.updates().get(i);
            String body = u.textBody() == null ? "" : u.textBody().strip();
            if (body.isEmpty() || isIntakeForm(body)) continue;
            comments.add(new CaseProgressRequest.Comment(
                    u.createdAt() == null
                            ? null
                            : u.createdAt().atZoneSameInstant(BUSINESS_ZONE).toLocalDate(),
                    u.creatorName(),
                    body));
        }
        if (comments.isEmpty()) {
            return null;
        }

        String line = solutionAi.summariseProgress(new CaseProgressRequest(
                branch, problem, status, supStatus, asOf, comments));
        if (line == null || line.isBlank()) return null;
        // The one rule the model is allowed to break and the report is not.
        return SolutionLine.splitCrossMonthRanges(line, asOf.getYear());
    }

    /**
     * The contact centre's intake form, dropped before the thread goes to the model.
     *
     * <p>It is the first comment on nearly every ticket, it begins "Ticket ID", and it
     * describes the request rather than any step taken on it — so it is both noise and,
     * at several hundred characters, most of the tokens.
     */
    private static boolean isIntakeForm(String body) {
        return body.startsWith("Ticket ID");
    }
}
