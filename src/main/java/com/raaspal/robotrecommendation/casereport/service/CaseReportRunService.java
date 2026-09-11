package com.raaspal.robotrecommendation.casereport.service;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.raaspal.robotrecommendation.casereport.dto.CaseReportRow;
import com.raaspal.robotrecommendation.casereport.entity.*;
import com.raaspal.robotrecommendation.casereport.repository.CaseReportDefinitionRepository;
import com.raaspal.robotrecommendation.casereport.repository.CaseReportRunRepository;
import com.raaspal.robotrecommendation.common.exception.BadRequestException;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.time.ZoneId;
import java.time.LocalDateTime;
import java.util.List;

/**
 * Generates a report once, then keeps it.
 *
 * <p><strong>The problem this solves.</strong> The source board is read live and the team
 * edits it all day, so regenerating an earlier date from monday does not reproduce that
 * date — it reports today's state under yesterday's heading. A case that has since closed
 * disappears; one whose status moved shows the new value. Anyone comparing the report they
 * sent on Tuesday with the report the system produces for Tuesday would find two different
 * documents and no way to tell which was real.
 *
 * <p>So the rows are frozen on first generation. After that, asking for that date returns
 * what was stored, not a fresh read.
 *
 * <p><strong>Except while it is still a draft.</strong> A run that has not been sent is
 * regenerated on request: the reviewer is still correcting it, and serving a stale copy
 * from an hour ago would be worse than re-reading the board. Once a run is
 * {@link CaseRunStatus#SENT} it is what the customer received and never moves again.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class CaseReportRunService {

    private final CaseReportDefinitionRepository definitions;
    private final CaseReportRunRepository runs;
    private final MkPendingReportGenerator mkGenerator;
    private final ObjectMapper objectMapper;

    /**
     * The rows for a date: stored if frozen, freshly generated and frozen otherwise.
     *
     * @param refresh regenerate even though a run exists. Ignored for a sent run, which
     *                cannot be replaced — the caller is told rather than silently obeyed.
     */
    @Transactional
    public List<CaseReportRow> rowsFor(String definitionCode, LocalDate asOf, boolean refresh) {
        CaseReportDefinition definition = requireDefinition(definitionCode);
        LocalDate today = LocalDate.now(ZoneId.of(definition.getScheduleZone()));

        if (asOf.isAfter(today)) {
            throw new BadRequestException(
                    "Cannot report on " + asOf + " — that day has not happened yet.");
        }

        CaseReportRun existing =
                runs.findByDefinitionIdAndRunDate(definition.getId(), asOf).orElse(null);

        if (existing != null && !refresh) {
            log.debug("Serving the frozen {} run for {} ({} rows)",
                    definitionCode, asOf, existing.getTicketCount());
            return parse(existing.getRowsJson());
        }

        if (existing != null && !existing.getStatus().isReplaceable()) {
            // Refusing rather than quietly returning the stored rows: somebody asked for a
            // regeneration and needs to know it did not happen, or they will believe the
            // report reflects the board when it reflects what was sent.
            throw new BadRequestException(
                    "The " + asOf + " report has already been sent and cannot be regenerated. "
                            + "What was delivered to the customer is the record.");
        }

        // ─────────────────────────────────────────────────────────────────────────────
        // The guard that matters most in this class.
        //
        // Generating is a LIVE read of monday, so producing a past date this way does not
        // reconstruct that day — it stamps today's board with an old heading. Every
        // status, every problem description, the set of open cases itself: all today's,
        // under a date they do not describe. Only the Days column would look different,
        // because that is computed from asOf, which is precisely what makes the result
        // convincing and therefore dangerous.
        //
        // A past date is answerable only from a record written at the time: a run frozen
        // that day (handled above), or the daily snapshot. Neither existing means the
        // honest answer is that nobody knows, and monday cannot be asked after the fact.
        // ─────────────────────────────────────────────────────────────────────────────
        if (asOf.isBefore(today)) {
            throw new BadRequestException(
                    "No report was generated for " + asOf + ", so it cannot be produced now. "
                            + "The boards are read live and are edited continuously, so "
                            + "generating it today would report today's cases under that "
                            + "date rather than what was actually open then. Reports are "
                            + "frozen on the day they are generated.");
        }

        List<CaseReportRow> rows = generate(definitionCode, definition, asOf);
        freeze(definition, asOf, existing, rows);
        return rows;
    }

    /**
     * Throw away a run.
     *
     * <p>For a generation that should not stand — one produced for the wrong date, or from
     * a board that was mid-edit. Deleted rather than marked DISCARDED because a discarded
     * row would still be found and served by the lookup above, which is the opposite of
     * what discarding means.
     *
     * <p>A sent run is never deletable. It is the record of what a customer received.
     */
    @Transactional
    public void discardRun(String definitionCode, LocalDate asOf) {
        CaseReportRun run = runs
                .findByDefinitionIdAndRunDate(requireDefinition(definitionCode).getId(), asOf)
                .orElseThrow(() -> new BadRequestException(
                        "There is no stored " + definitionCode + " run for " + asOf + "."));

        if (!run.getStatus().isReplaceable()) {
            throw new BadRequestException(
                    "The " + asOf + " report has been sent and cannot be discarded.");
        }

        runs.delete(run);
        log.info("Discarded the {} run for {}", definitionCode, asOf);
    }

    /** The stored run for a date, if there is one. */
    @Transactional(readOnly = true)
    public CaseReportRun findRun(String definitionCode, LocalDate asOf) {
        return runs.findByDefinitionIdAndRunDate(requireDefinition(definitionCode).getId(), asOf)
                .orElse(null);
    }

    private List<CaseReportRow> generate(String code,
                                         CaseReportDefinition definition,
                                         LocalDate asOf) {
        if (!CaseReportDefinition.MK_PENDING.equals(code)) {
            // One generator exists so far. Named explicitly rather than falling through to
            // it, so adding AOT is a compile-time obligation and not a silent wrong report.
            throw new BadRequestException("No generator is wired for report " + code);
        }
        return mkGenerator.generate(asOf);
    }

    private void freeze(CaseReportDefinition definition,
                        LocalDate asOf,
                        CaseReportRun existing,
                        List<CaseReportRow> rows) {

        CaseReportRun run = existing != null ? existing : CaseReportRun.builder()
                .definitionId(definition.getId())
                .runDate(asOf)
                .build();

        run.setRowsJson(write(rows));
        run.setTicketCount(rows.size());
        run.setGeneratedAt(LocalDateTime.now());
        run.setErrorMessage(null);

        // Straight to AWAITING_APPROVAL: GENERATING describes a run in flight, and by the
        // time there are rows to store the generating is over. A run found sitting in
        // GENERATING is therefore a crash, which is worth being able to tell apart.
        run.setStatus(CaseRunStatus.AWAITING_APPROVAL);

        runs.save(run);

        log.info("Froze the {} run for {}: {} rows ({})",
                definition.getCode(), asOf, rows.size(),
                existing == null ? "new" : "replaced a draft");
    }

    private CaseReportDefinition requireDefinition(String code) {
        return definitions.findByCode(code)
                .orElseThrow(() -> new BadRequestException(
                        "No report definition with code " + code
                                + ". It is seeded on startup; check the logs for a failure."));
    }

    private String write(List<CaseReportRow> rows) {
        try {
            return objectMapper.writeValueAsString(rows);
        } catch (Exception e) {
            throw new IllegalStateException("Could not serialise the report rows", e);
        }
    }

    private List<CaseReportRow> parse(String json) {
        if (json == null || json.isBlank()) return List.of();
        try {
            return objectMapper.readValue(json, new TypeReference<List<CaseReportRow>>() {});
        } catch (Exception e) {
            // A stored run that cannot be read is a bug worth surfacing, not a reason to
            // silently show an empty report as though the day had no cases.
            throw new IllegalStateException(
                    "Stored report rows could not be read back. The run is present but "
                            + "unreadable, so regenerate it with ?refresh=true.", e);
        }
    }
}
