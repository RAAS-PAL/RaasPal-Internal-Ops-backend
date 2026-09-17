package com.raaspal.robotrecommendation.casereport.service;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.raaspal.robotrecommendation.casereport.dto.CaseReportRow;
import com.raaspal.robotrecommendation.casereport.dto.CaseRowEdit;
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
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Function;
import java.util.stream.Collectors;

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
 *
 * <p><strong>And the draft is the thing people correct.</strong> The board is wrong in
 * small ways every day — a serial from the intake form, an open date the team counts
 * from a later event — and the fix the reviewer wants is to overtype the cell on the
 * report, not to go and argue with monday first. {@link #editRow} writes the correction
 * into the stored rows, and a regeneration keeps every row a person has edited.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class CaseReportRunService {

    private final CaseReportDefinitionRepository definitions;
    private final CaseReportRunRepository runs;
    private final MkPendingReportGenerator mkGenerator;
    private final CleaningPendingReportGenerator cleaningGenerator;
    private final AotgaReportGenerator aotgaGenerator;
    private final OnHoldReportGenerator onHoldGenerator;
    private final SlaCalculator slaCalculator;
    private final ObjectMapper objectMapper;
    private final CaseReportExcelWriter excelWriter;

    /**
     * The generations running right now, by sheet and date.
     *
     * <p>A generation is minutes of monday and model calls. When a second request for the
     * same sheet and date arrives while one is running — a client that gave up and asked
     * again, two reviewers opening the same tab — it waits for the first and gets its rows,
     * rather than starting another that reads the same board, pays for the same model
     * calls, and then overwrites the same run. In-memory, because there is one instance.
     */
    private final ConcurrentHashMap<String, CompletableFuture<List<CaseReportRow>>> inFlight =
            new ConcurrentHashMap<>();

    /** A built workbook and the name it should download as. */
    public record Export(byte[] bytes, String filename) {
    }

    /**
     * The sheet for a date as a workbook, for the team to attach or forward.
     *
     * <p>Built from {@link #rowsFor} with {@code refresh=false}, so it is exactly what
     * the screen shows for that date, corrections included, and a date that has never
     * been generated is generated and frozen first - the same as opening it.
     */
    @Transactional
    public Export export(String definitionCode, LocalDate asOf) {
        CaseReportDefinition definition = requireDefinition(definitionCode);
        List<CaseReportRow> rows = rowsFor(definitionCode, asOf, false).stream()
                .filter(row -> !row.removed())
                .toList();
        return new Export(excelWriter.write(definition, asOf, rows), excelWriter.filename(definition, asOf));
    }

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
            if (existing != null) {
                // A draft from an earlier day, asked to be regenerated. Same reason as
                // below: the board describes today, not that day. Say so, rather than
                // claiming nothing was generated when the reviewer is looking at it.
                throw new BadRequestException(
                        "The " + asOf + " report is from an earlier day and cannot be "
                                + "regenerated: the board is read live and no longer shows "
                                + "what was open then. Correct its rows by editing them.");
            }
            throw new BadRequestException(
                    "No report was generated for " + asOf + ", so it cannot be produced now. "
                            + "The boards are read live and are edited continuously, so "
                            + "generating it today would report today's cases under that "
                            + "date rather than what was actually open then. Reports are "
                            + "frozen on the day they are generated.");
        }

        String key = definitionCode + '|' + asOf;
        CompletableFuture<List<CaseReportRow>> mine = new CompletableFuture<>();
        CompletableFuture<List<CaseReportRow>> running = inFlight.putIfAbsent(key, mine);
        if (running != null) {
            log.info("A {} generation for {} is already running; waiting for its rows",
                    definitionCode, asOf);
            try {
                return running.join();
            } catch (CompletionException e) {
                // The same failure the first request saw, with its message intact.
                if (e.getCause() instanceof RuntimeException re) throw re;
                throw e;
            }
        }
        try {
            List<CaseReportRow> rows = generate(definitionCode, definition, asOf);
            if (existing != null) {
                rows = keepEditedRows(parse(existing.getRowsJson()), rows);
            }
            freeze(definition, asOf, existing, rows);
            mine.complete(rows);
            return rows;
        } catch (RuntimeException e) {
            mine.completeExceptionally(e);
            throw e;
        } finally {
            inFlight.remove(key, mine);
        }
    }

    /**
     * Correct one row of a stored draft.
     *
     * <p>Every printed cell is replaced by what the form sent. Days and SLA are worked out
     * again from the Open Date unless the editor typed them, so correcting a date does not
     * leave yesterday's arithmetic beside it; a held case stays held, because On Hold is a
     * fact about the ticket's status, not its dates.
     *
     * <p>The row is marked edited, which is what a later regeneration honours.
     *
     * @return the row as stored
     */
    @Transactional
    public CaseReportRow editRow(String definitionCode,
                                 LocalDate asOf,
                                 String sourceItemId,
                                 CaseRowEdit edit) {
        CaseReportDefinition definition = requireDefinition(definitionCode);
        CaseReportRun run = requireDraft(definitionCode, definition, asOf);

        List<CaseReportRow> rows = new ArrayList<>(parse(run.getRowsJson()));
        int index = indexOf(rows, sourceItemId);
        if (index < 0) {
            throw new BadRequestException(
                    "The " + asOf + " report has no row for ticket " + sourceItemId + ".");
        }

        CaseReportRow before = rows.get(index);
        CaseReportRow after = build(definition, asOf, before.no(), before.sourceItemId(),
                before, edit);

        rows.set(index, after);
        run.setRowsJson(write(rows));
        runs.save(run);

        log.info("Edited row {} (ticket {}) of the {} run for {}",
                after.no(), sourceItemId, definitionCode, asOf);
        return after;
    }

    /**
     * Add a row the board does not have.
     *
     * <p>For the case the team is tracking that the board is not — a ticket sitting in
     * another group, or one that never got a ticket. It goes at the bottom, carries a
     * {@link CaseReportRow#MANUAL_PREFIX} id in place of a ticket, and is kept through
     * regeneration the way an edited row is. Nothing is written to monday.
     */
    @Transactional
    public CaseReportRow addRow(String definitionCode, LocalDate asOf, CaseRowEdit edit) {
        CaseReportDefinition definition = requireDefinition(definitionCode);
        CaseReportRun run = requireDraft(definitionCode, definition, asOf);

        List<CaseReportRow> rows = new ArrayList<>(parse(run.getRowsJson()));
        String id = CaseReportRow.MANUAL_PREFIX + UUID.randomUUID().toString().substring(0, 8);
        CaseReportRow row = build(definition, asOf, rows.size() + 1, id, null, edit);

        rows.add(row);
        run.setRowsJson(write(rows));
        run.setTicketCount((int) rows.stream().filter(r -> !r.removed()).count());
        runs.save(run);

        log.info("Added row {} ({}) to the {} run for {}", row.no(), id, definitionCode, asOf);
        return row;
    }

    /**
     * Take a row off this date's report.
     *
     * <p>The RE team drops tickets the board still lists — resolved but not yet closed,
     * or not the customer's concern that morning — and the sheet has to be able to say
     * so. A board row is kept in the stored run, hidden and numbered 0, which is how a
     * later regeneration knows not to bring it back and how {@link #restoreRow} can undo
     * it. Nothing is written to monday. A row added by hand is deleted outright: nothing
     * would bring it back, and there is nothing to restore it from.
     */
    @Transactional
    public void removeRow(String definitionCode, LocalDate asOf, String sourceItemId) {
        CaseReportDefinition definition = requireDefinition(definitionCode);
        CaseReportRun run = requireDraft(definitionCode, definition, asOf);

        List<CaseReportRow> rows = new ArrayList<>(parse(run.getRowsJson()));
        int index = indexOf(rows, sourceItemId);
        if (index < 0) {
            throw new BadRequestException(
                    "The " + asOf + " report has no row " + sourceItemId + ".");
        }

        if (rows.get(index).isManual()) {
            rows.remove(index);
        } else {
            rows.set(index, rows.get(index).withRemoved(true));
        }
        store(run, rows);

        log.info("Removed row {} from the {} run for {}", sourceItemId, definitionCode, asOf);
    }

    /**
     * Put a removed board row back on the sheet, where the sheet's order would have it.
     *
     * @return the row as stored, with its number back
     */
    @Transactional
    public CaseReportRow restoreRow(String definitionCode, LocalDate asOf, String sourceItemId) {
        CaseReportDefinition definition = requireDefinition(definitionCode);
        CaseReportRun run = requireDraft(definitionCode, definition, asOf);

        List<CaseReportRow> rows = new ArrayList<>(parse(run.getRowsJson()));
        int index = indexOf(rows, sourceItemId);
        if (index < 0 || !rows.get(index).removed()) {
            throw new BadRequestException(
                    "The " + asOf + " report has no removed row " + sourceItemId + ".");
        }

        rows.set(index, rows.get(index).withRemoved(false));
        List<CaseReportRow> stored = store(run, rows);

        log.info("Restored row {} to the {} run for {}", sourceItemId, definitionCode, asOf);
        return stored.get(index);
    }

    /** Renumber, count what is on the sheet, and save. */
    private List<CaseReportRow> store(CaseReportRun run, List<CaseReportRow> rows) {
        List<CaseReportRow> numbered = renumber(rows);
        run.setRowsJson(write(numbered));
        run.setTicketCount((int) numbered.stream().filter(row -> !row.removed()).count());
        runs.save(run);
        return numbered;
    }

    /** The stored draft for a date, or the reason there is nothing to change. */
    private CaseReportRun requireDraft(String definitionCode,
                                       CaseReportDefinition definition,
                                       LocalDate asOf) {
        CaseReportRun run = runs.findByDefinitionIdAndRunDate(definition.getId(), asOf)
                .orElseThrow(() -> new BadRequestException(
                        "There is no generated " + definitionCode + " report for " + asOf
                                + " to change. Generate it first."));

        if (!run.getStatus().isReplaceable()) {
            throw new BadRequestException(
                    "The " + asOf + " report has already been sent and cannot be changed.");
        }
        return run;
    }

    /**
     * A row from the form.
     *
     * @param previous what the row said before the edit, or null for a new row. Two
     *                 things survive from it: a held case stays held when the editor
     *                 leaves SLA blank, and the AOTGA part-tracking fields are carried
     *                 across unchanged, since the form does not yet offer them — an edit
     *                 to the Problem cell must not blank the Required Part beside it.
     */
    private CaseReportRow build(CaseReportDefinition definition,
                                LocalDate asOf,
                                int no,
                                String sourceItemId,
                                CaseReportRow previous,
                                CaseRowEdit edit) {
        SlaStatus previousSla = previous == null ? null : previous.sla();
        LocalDate openDate = edit.openDate();
        String province = blankToNull(edit.province());

        Integer days = edit.days() != null
                ? edit.days()
                : openDate == null ? null : SlaCalculator.daysOpen(openDate, asOf);

        SlaStatus sla;
        if (edit.sla() != null) {
            sla = edit.sla();
        } else if (previousSla == SlaStatus.ON_HOLD) {
            sla = SlaStatus.ON_HOLD;
        } else {
            sla = slaCalculator.evaluate(null, null, province, openDate, asOf,
                    definition.getSlaDaysMetro(), definition.getSlaDaysUpcountry());
        }

        return new CaseReportRow(
                no,
                blankToNull(edit.project()),
                blankToNull(edit.branch()),
                blankToNull(edit.robot()),
                blankToNull(edit.serialNumber()),
                blankToNull(edit.problem()),
                blankToNull(edit.solution()),
                openDate,
                edit.reOnSite(),
                days,
                sla,
                sla == null ? "" : sla.label(),
                previous == null ? null : previous.requiredPart(),
                previous == null ? null : previous.waiting(),
                previous == null ? null : previous.waitingFrom(),
                previous == null ? null : previous.partReceived(),
                previous == null ? null : previous.agingAfterReceived(),
                province,
                // Carried like the part fields: the form has no Board control, and an
                // edited On Hold row must not drop out of the reviewer's filter.
                previous == null ? null : previous.board(),
                sourceItemId,
                true,
                previous != null && previous.removed());
    }

    private static int indexOf(List<CaseReportRow> rows, String sourceItemId) {
        for (int i = 0; i < rows.size(); i++) {
            if (sourceItemId.equals(rows.get(i).sourceItemId())) return i;
        }
        return -1;
    }

    /** 1..n over the rows on the sheet; a removed row is numbered 0 and keeps its place. */
    private static List<CaseReportRow> renumber(List<CaseReportRow> rows) {
        List<CaseReportRow> out = new ArrayList<>(rows.size());
        int next = 1;
        for (CaseReportRow row : rows) {
            out.add(row.withNo(row.removed() ? 0 : next++));
        }
        return out;
    }

    /**
     * The regenerated rows, with every row a person edited carried over unchanged, every
     * row a person removed kept off the sheet, and every row a person added kept at the
     * bottom.
     *
     * <p>Edited and removed rows are matched by ticket, so a regeneration that reorders
     * the sheet still finds them. A removed row takes the board's fresh content but stays
     * hidden: if it is ever restored it should say what the board says now. An edited or
     * removed row whose ticket has left the board is dropped with the rest: the case
     * closed, and there is nothing left to correct or hide. Added rows have no ticket to
     * leave, so they stay until somebody removes them. Renumbered afterwards because the
     * generator numbered before the swap.
     */
    private static List<CaseReportRow> keepEditedRows(List<CaseReportRow> stored,
                                                      List<CaseReportRow> fresh) {
        Map<String, CaseReportRow> edited = stored.stream()
                .filter(CaseReportRow::edited)
                .filter(row -> row.sourceItemId() != null && !row.isManual())
                .collect(Collectors.toMap(CaseReportRow::sourceItemId, Function.identity(),
                        (a, b) -> a));
        java.util.Set<String> removed = stored.stream()
                .filter(CaseReportRow::removed)
                .map(CaseReportRow::sourceItemId)
                .filter(java.util.Objects::nonNull)
                .collect(Collectors.toSet());
        List<CaseReportRow> manual = stored.stream().filter(CaseReportRow::isManual).toList();
        if (edited.isEmpty() && removed.isEmpty() && manual.isEmpty()) {
            return fresh;
        }

        List<CaseReportRow> merged = new ArrayList<>(fresh.size() + manual.size());
        int kept = 0;
        for (CaseReportRow row : fresh) {
            CaseReportRow keep = edited.get(row.sourceItemId());
            if (keep != null) kept++;
            CaseReportRow next = keep != null ? keep : row;
            merged.add(removed.contains(row.sourceItemId()) ? next.withRemoved(true) : next);
        }
        merged.addAll(manual);
        log.info("Regenerated with {} edited row(s) kept and {} added row(s) carried over",
                kept, manual.size());
        return renumber(merged);
    }

    private static String blankToNull(String value) {
        if (value == null) return null;
        String trimmed = value.strip();
        return trimmed.isEmpty() ? null : trimmed;
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
        // Each code named explicitly rather than falling through to a default, so adding
        // AOTGA is a compile-time obligation and not a silent wrong report.
        return switch (code) {
            case CaseReportDefinition.MK_PENDING ->
                    mkGenerator.generate(MkPendingReportGenerator.Scope.MK, asOf);
            case CaseReportDefinition.DELIVERY_PENDING ->
                    mkGenerator.generate(MkPendingReportGenerator.Scope.OTHER, asOf);
            case CaseReportDefinition.CLEANING_PENDING ->
                    cleaningGenerator.generate(CleaningPendingReportGenerator.Scope.CLEANING, asOf);
            case CaseReportDefinition.MAKRO_PENDING ->
                    cleaningGenerator.generate(CleaningPendingReportGenerator.Scope.MAKRO, asOf);
            case CaseReportDefinition.AOTGA_PENDING -> aotgaGenerator.generate(asOf);
            case CaseReportDefinition.ON_HOLD_PENDING -> onHoldGenerator.generate(asOf);
            default -> throw new BadRequestException("No generator is wired for report " + code);
        };
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
        run.setTicketCount((int) rows.stream().filter(r -> !r.removed()).count());
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
