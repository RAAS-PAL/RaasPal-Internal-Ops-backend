package com.raaspal.robotrecommendation.casereport.service;

import com.raaspal.robotrecommendation.ai.service.CaseSolutionAiService;
import com.raaspal.robotrecommendation.casereport.adapters.monday.dto.MondayItem;
import com.raaspal.robotrecommendation.casereport.adapters.monday.dto.MondayUpdate;
import com.raaspal.robotrecommendation.casereport.dto.CaseProgressRequest;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.time.LocalDate;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Locale;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.function.Supplier;
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
public class SolutionLineWriter {

    /**
     * The zone a comment's date is taken in.
     *
     * <p>monday's timestamps are UTC, and the Solution line prints a date for each step.
     * Read as UTC, anything posted before 07:00 Bangkok would be dated the day before.
     */
    private static final ZoneId BUSINESS_ZONE = ZoneId.of("Asia/Bangkok");

    /**
     * What the RE team writes on a ticket nobody has commented on yet: the case has been
     * received and is being looked at. Dated the day it opened.
     */
    public static final String OPENER = "อยู่ระหว่างตรวจสอบและประเมินอาการหุ่นยนต์";

    /** {@code 16-Sep}, the entry date form the workbook uses. */
    private static final DateTimeFormatter ENTRY_DATE = DateTimeFormatter.ofPattern("dd-MMM", Locale.ENGLISH);

    private final CaseSolutionAiService solutionAi;

    /**
     * Where the model calls run. One call per ticket, and a sheet has dozens of tickets:
     * in series that was three minutes for On Hold, which is longer than any client waits.
     * Bounded, because the API has rate limits and the box has two cores; daemon, so a
     * stuck call cannot keep the JVM from shutting down.
     */
    private final ExecutorService pool;

    @Autowired
    public SolutionLineWriter(CaseSolutionAiService solutionAi,
                              @Value("${app.casereport.solution-concurrency:6}") int concurrency) {
        this.solutionAi = solutionAi;
        this.pool = Executors.newFixedThreadPool(Math.max(1, concurrency), r -> {
            Thread t = new Thread(r, "solution-writer");
            t.setDaemon(true);
            return t;
        });
    }

    /** Tests: the default concurrency. */
    public SolutionLineWriter(CaseSolutionAiService solutionAi) {
        this(solutionAi, 6);
    }

    /**
     * Build every row at once, on the pool, and return them in the order given.
     *
     * <p>Each task is one row's construction with its {@link #write} call inside, so the
     * generator's loop stays a loop that decides what belongs on the sheet, and only the
     * slow part moves off the request thread. A task that fails fails the sheet — the
     * same as it did in series — with the original exception rather than a wrapped one,
     * so the message that reaches the reviewer is unchanged.
     */
    public <T> java.util.List<T> buildAll(java.util.List<Supplier<T>> tasks) {
        java.util.List<Future<T>> futures = new ArrayList<>(tasks.size());
        for (Supplier<T> task : tasks) {
            futures.add(pool.submit(task::get));
        }
        java.util.List<T> out = new ArrayList<>(futures.size());
        for (Future<T> f : futures) {
            try {
                out.add(f.get());
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new IllegalStateException("Interrupted while writing Solution cells", e);
            } catch (ExecutionException e) {
                if (e.getCause() instanceof RuntimeException re) throw re;
                throw new IllegalStateException(e.getCause());
            }
        }
        return out;
    }

    /**
     * @param typed     the board's Solution cell, used as-is when not blank
     * @param branch    the row's branch or site label, context for the model
     * @param problem   the Main Issue cell
     * @param status    the Status cell, so the model can finish with the current state
     * @param supStatus the Sup Status cell
     * @param openDate  when the case opened, which dates the opener on a silent ticket;
     *                  null falls back to the report date
     * @return the cell, one dated entry per line; never blank on a board row
     */
    public String write(MondayItem item,
                        String typed,
                        String branch,
                        String problem,
                        String status,
                        String supStatus,
                        LocalDate openDate,
                        LocalDate asOf) {
        if (typed != null && !typed.isBlank()) {
            return SolutionLine.oneEntryPerLine(typed);
        }
        List<CaseProgressRequest.Comment> comments = commentsOf(item);
        String line = comments.isEmpty() ? null : solutionAi.summariseProgress(
                new CaseProgressRequest(branch, problem, status, supStatus, asOf, comments));

        // A blank cell used to be the honest answer for a ticket with nothing in it. The
        // RE team's sheet never leaves one blank: a case just opened is "being assessed",
        // and they write that. So does this, dated the day the case opened, which is what
        // the reviewer would have typed.
        if (line == null || line.isBlank()) {
            LocalDate on = openDate != null ? openDate : asOf;
            return on.format(ENTRY_DATE) + ' ' + OPENER;
        }
        // The one rule the model is allowed to break and the report is not — then the
        // layout, which the model is not asked for at all.
        return SolutionLine.oneEntryPerLine(
                SolutionLine.splitCrossMonthRanges(line, asOf.getYear()));
    }

    /**
     * The ticket's comment thread as the model reads it: oldest first, dated in Bangkok,
     * intake form and empty comments dropped. Shared with {@link PartsLineWriter}, which
     * sends the same thread to a different prompt — one reading of the thread, so the
     * two cannot disagree about which comments exist.
     */
    static List<CaseProgressRequest.Comment> commentsOf(MondayItem item) {
        List<CaseProgressRequest.Comment> comments = new ArrayList<>();
        if (item.updates() == null) {
            return comments;
        }
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
        return comments;
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
