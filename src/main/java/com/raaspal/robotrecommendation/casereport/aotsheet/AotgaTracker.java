package com.raaspal.robotrecommendation.casereport.aotsheet;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.raaspal.robotrecommendation.casereport.service.SlaCalculator;
import com.raaspal.robotrecommendation.common.exception.BadRequestException;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * AOTGA's cases as a workflow, not an SLA: where each one is between AOT reporting a
 * problem and RAASPAL claiming the old part back from the manufacturer.
 *
 * <p>Everything up to the old part coming back is read from AOT's sheet:
 * <ol>
 *   <li>{@link Stage#REPORTED} - a white row with an AOT ticket;</li>
 *   <li>{@link Stage#PART_REQUESTED} - its "Request for Spare part" is filled;</li>
 *   <li>{@link Stage#OLD_PART_BACK} - the row is blue: AOT has sent the old part back.</li>
 * </ol>
 * The last step, {@link Stage#CLAIMED} - sent to the manufacturer - is RAASPAL's own and is
 * recorded here ({@link AotgaClaim}): one dropdown on the case, set back to undo it. A white
 * row whose "Spare Part Received" has a date is at that step of itself
 * ({@link Case#sentFromSheet}): the sheet says so, and there is nothing to change here. A
 * sent case stays on the list, at that stage.
 *
 * <p>A case whose "Request for Spare part" asks RAASPAL to look into it further instead
 * ({@link AotSheetProperties#asksForReview}) has no part to follow and two steps of its own,
 * by colour alone: {@link Stage#PENDING_REVIEW} while white, {@link Stage#CASE_CLOSED} once blue.
 *
 * <p>Every case on the sheet is tracked: a blue row waits for its claim until one is
 * recorded, however long ago it turned blue.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class AotgaTracker {

    static final ZoneId BUSINESS_ZONE = ZoneId.of("Asia/Bangkok");
    /** Longest note a claim may carry. */
    static final int NOTE_MAX = 500;

    /** The spare-part steps in order, then the review steps; sorting by stage keeps them so. */
    public enum Stage { REPORTED, PART_REQUESTED, OLD_PART_BACK, CLAIMED, PENDING_REVIEW, CASE_CLOSED }

    /**
     * One AOTGA case.
     *
     * @param days          days since the issue date; the issue day itself is not counted
     * @param colour        the row's colour on the sheet, {@code #rrggbb}
     * @param colourLabel   the name the team gave that colour; null for none
     * @param sheetRow      the row's number on the sheet at the last sync
     * @param oldPartBackOn the day the sync saw the row turn blue; null when it was blue before
     * @param sentFromSheet {@link Stage#CLAIMED} because the sheet has the "Spare Part Received"
     *                      date, not because it was marked here: shown as sent, not a choice
     */
    public record Case(String ticketNo,
                       String site,
                       String robot,
                       String serial,
                       String problem,
                       LocalDate issueDate,
                       Integer days,
                       String status,
                       String colour,
                       String colourLabel,
                       Stage stage,
                       String requestedPart,
                       LocalDate partSentOn,
                       LocalDate oldPartBackOn,
                       LocalDate claimedOn,
                       String claimNote,
                       String claimedBy,
                       Integer sheetRow,
                       boolean sentFromSheet) {
    }

    /**
     * @param ready       the sheet is linked, syncing and has synced once; false shows nothing
     * @param asOf        the day the list is of
     * @param live        today's list, read now; false for a past day's saved copy
     * @param historyFrom the first day there is a copy of; null while there is none
     * @param cases       every case on the sheet with a ticket number, oldest issue first
     * @param repeated    rows whose AOT ticket number is on another row too: listed by row so
     *                    they can be fixed there, never tracked or claimed - one number on two
     *                    rows cannot be told which case it means
     * @param noCopyFor   a past day asked for that has no copy (it is before history started):
     *                    the list is today's instead, and says so; null otherwise
     * @param noTicket    rows of the sheet with no AOT ticket number: listed so they can be
     *                    fixed there, never tracked or claimed - without a number a row
     *                    cannot be told from the next
     */
    public record View(boolean ready,
                       LocalDate asOf,
                       boolean live,
                       LocalDate historyFrom,
                       LocalDateTime lastSyncedAt,
                       List<Case> cases,
                       List<Case> noTicket,
                       List<Case> repeated,
                       LocalDate noCopyFor) {

        static View notReady(LocalDate asOf, boolean live) {
            return new View(false, asOf, live, null, null, List.of(), List.of(), List.of(), null);
        }

        View withHistoryFrom(LocalDate from) {
            return new View(ready, asOf, live, from, lastSyncedAt, cases, noTicket, repeated, noCopyFor);
        }

        /** Today's list standing in for a past day that has no copy. */
        View standingInFor(LocalDate day) {
            return new View(ready, asOf, live, historyFrom, lastSyncedAt, cases, noTicket, repeated, day);
        }

        /** A saved copy, read back for its day; a copy kept before a list existed has it empty. */
        View asPast(LocalDate day, LocalDate from) {
            return new View(true, day, false, from, lastSyncedAt, cases,
                    noTicket == null ? List.of() : noTicket, repeated == null ? List.of() : repeated, null);
        }
    }

    /** @param claimedOn null means today */
    public record ClaimRequest(String ticketNo, LocalDate claimedOn, String note) {
    }


    private final AotSheetSettingsService settings;
    private final AotSheetSyncService sync;
    private final AotgaSheetRows sheet;
    private final AotgaClaimRepository claims;
    private final AotgaSnapshotRepository snapshots;
    private final ObjectMapper objectMapper;

    /** Today's list, read now - and kept as today's copy. */
    public View view() {
        View live = live();
        if (live.ready()) record(live);
        return live.withHistoryFrom(historyFrom());
    }

    /**
     * The list for a day: today's read now, a past day's from the copy kept that day. A
     * week or a month is asked for by its last day - or today, while it is still running.
     * A day with no copy - before history started - gets today's list, marked
     * {@link View#noCopyFor}: every case is on it, as it stands now, so the console can
     * still pick out that period's cases by their issue date.
     */
    public View view(LocalDate day) {
        LocalDate today = LocalDate.now(BUSINESS_ZONE);
        if (day == null || !day.isBefore(today)) return view();
        LocalDate from = historyFrom();
        return snapshots.findById(day)
                .map(s -> read(s.getViewJson()))
                .map(v -> v.asPast(day, from))
                .orElseGet(() -> view().standingInFor(day));
    }

    /**
     * The latest copy kept on or before a day, as it was kept: nothing is read from the
     * sheet. For a public link, so that opening one never starts a sync of AOT's sheet.
     */
    public Optional<View> latestCopy(LocalDate onOrBefore) {
        return snapshots.findFirstByRunDateLessThanEqualOrderByRunDateDesc(onOrBefore)
                .map(s -> read(s.getViewJson()));
    }

    /** The last two weeks' copies on or before a day, newest first, as kept. */
    public List<View> recentCopies(LocalDate onOrBefore) {
        return snapshots.findTop14ByRunDateLessThanEqualOrderByRunDateDesc(onOrBefore).stream()
                .map(s -> read(s.getViewJson()))
                .toList();
    }

    /** Keeps today's copy current; the 15-minute refresh calls this after syncing the sheet. */
    public void recordToday() {
        View live = live();
        if (live.ready()) record(live);
    }

    private View live() {
        LocalDate today = LocalDate.now(BUSINESS_ZONE);
        AotSheetProperties props = settings.effective();
        if (!props.isConfigured() || !props.isSyncEnabled()) return View.notReady(today, true);
        List<AotgaSheetRows.Ticket> rows = sheet.read();
        LocalDateTime synced = sync.lastSyncedAt();
        if (synced == null) return View.notReady(today, true);
        return build(rows, claims.findBySpreadsheetId(props.getSpreadsheetId()), props, today, synced,
                sync.unidentifiedRows(), sync.repeatedRows());
    }

    private void record(View view) {
        try {
            snapshots.save(AotgaSnapshot.builder()
                    .runDate(view.asOf())
                    .spreadsheetId(settings.effective().getSpreadsheetId())
                    .viewJson(objectMapper.writeValueAsString(view))
                    .updatedAt(OffsetDateTime.now())
                    .build());
        } catch (JsonProcessingException | RuntimeException e) {
            // The list is still shown; only today's copy is not updated this time.
            log.warn("Could not keep today's AOTGA copy", e);
        }
    }

    private View read(String json) {
        try {
            return objectMapper.readValue(json, View.class);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("A saved AOTGA copy could not be read", e);
        }
    }

    private LocalDate historyFrom() {
        return snapshots.findFirstByOrderByRunDateAsc().map(AotgaSnapshot::getRunDate).orElse(null);
    }

    static View build(List<AotgaSheetRows.Ticket> rows,
                      List<AotgaClaim> claimList,
                      AotSheetProperties props,
                      LocalDate today,
                      LocalDateTime synced) {
        return build(rows, claimList, props, today, synced, List.of(), List.of());
    }

    /** The tracker from the synced rows and the claims: no I/O, so it can be tested as it is. */
    static View build(List<AotgaSheetRows.Ticket> rows,
                      List<AotgaClaim> claimList,
                      AotSheetProperties props,
                      LocalDate today,
                      LocalDateTime synced,
                      List<AotSheetCase> unidentified,
                      List<AotSheetCase> repeatedRows) {
        Map<String, AotgaClaim> byTicket = claimList.stream()
                .collect(Collectors.toMap(AotgaClaim::getTicketNo, Function.identity(), (a, b) -> a));
        Set<String> blue = props.getClosedColours().stream()
                .map(AotSheetRowMapper::normaliseColour)
                .filter(Objects::nonNull)
                .collect(Collectors.toSet());
        Map<String, String> labels = props.getColourLabels().stream()
                .filter(l -> !l.ignored() && AotSheetRowMapper.normaliseColour(l.colour()) != null
                        && !AotSheetProperties.isBlank(l.label()))
                .collect(Collectors.toMap(l -> AotSheetRowMapper.normaliseColour(l.colour()),
                        l -> l.label().trim(), (a, b) -> a));

        List<Case> cases = new ArrayList<>();
        for (AotgaSheetRows.Ticket t : rows) {
            if (t.ticketNo() == null) continue;
            AotgaClaim claim = byTicket.get(t.ticketNo());
            boolean review = props.asksForReview(t.requestedPart());
            if (t.open()) {
                boolean sentFromSheet = !review && t.partSentOn() != null;
                Stage stage = review ? Stage.PENDING_REVIEW
                        : sentFromSheet ? Stage.CLAIMED
                        : !AotSheetProperties.isBlank(t.requestedPart()) ? Stage.PART_REQUESTED
                        : Stage.REPORTED;
                cases.add(toCase(t, stage, null, today, labels, sentFromSheet));
                continue;
            }
            // Not open: blue, or deleted from the sheet - only the colour tells which.
            if (!blue.contains(AotSheetRowMapper.normaliseColour(t.colour()))) continue;
            if (review) {
                cases.add(toCase(t, Stage.CASE_CLOSED, null, today, labels, false));
                continue;
            }
            boolean sent = claim != null && claim.getClaimedOn() != null;
            cases.add(toCase(t, sent ? Stage.CLAIMED : Stage.OLD_PART_BACK, claim, today, labels, false));
        }
        cases.sort(Comparator.comparing(Case::issueDate, Comparator.nullsLast(Comparator.naturalOrder())));
        // Rows the tracker cannot place, by their row on the sheet: where to look to fix them.
        List<Case> noTicket = unidentified.stream().map(c -> apart(c, null, blue, labels, props, today)).toList();
        List<Case> repeated = repeatedRows.stream()
                .map(c -> apart(c, c.rowId(), blue, labels, props, today))
                .sorted(AotgaSort.of("ticket", "asc").comparator())
                .toList();
        return new View(true, today, true, null, synced, List.copyOf(cases), noTicket, repeated, null);
    }

    /** A row listed apart: what the sheet says of it, its stage by colour, never a claim. */
    private static Case apart(AotSheetCase c, String ticketNo, Set<String> blue, Map<String, String> labels,
                              AotSheetProperties props, LocalDate today) {
        String colour = c.colour();
        boolean review = props.asksForReview(c.requestedPart());
        Stage stage = review ? (blue.contains(colour) ? Stage.CASE_CLOSED : Stage.PENDING_REVIEW)
                : blue.contains(colour) ? Stage.OLD_PART_BACK
                : !AotSheetProperties.isBlank(c.requestedPart()) ? Stage.PART_REQUESTED
                : Stage.REPORTED;
        return new Case(ticketNo, c.site(), c.model(), c.serialNumbers(), c.problem(), c.openDate(),
                c.openDate() == null ? null : SlaCalculator.daysOpen(c.openDate(), today), c.status(),
                colour, colour == null ? null : labels.get(colour), stage, c.requestedPart(), null,
                null, null, null, null, c.sheetRow(), false);
    }

    private static Case toCase(AotgaSheetRows.Ticket t, Stage stage, AotgaClaim claim, LocalDate today,
                               Map<String, String> labels, boolean sentFromSheet) {
        String colour = AotSheetRowMapper.normaliseColour(t.colour());
        return new Case(
                t.ticketNo(),
                t.site(),
                t.robot(),
                t.serial(),
                t.problem(),
                t.openDate(),
                t.openDate() == null ? null : SlaCalculator.daysOpen(t.openDate(), today),
                t.status(),
                colour,
                colour == null ? null : labels.get(colour),
                stage,
                t.requestedPart(),
                t.partSentOn(),
                claim == null ? null : claim.getOldPartBackOn(),
                claim == null ? null : claim.getClaimedOn(),
                claim == null ? null : claim.getNote(),
                claim == null ? null : claim.getClaimedBy(),
                t.sheetRow(),
                sentFromSheet);
    }

    /** Records the claim of a case's old part; recording it again corrects it. */
    public View claim(ClaimRequest request, String user) {
        AotSheetProperties props = settings.effective();
        if (!props.isConfigured()) throw new BadRequestException("Link AOT's Google Sheet first.");
        String ticketNo = trim(request.ticketNo());
        if (ticketNo == null) throw new BadRequestException("Which AOT ticket was claimed?");
        LocalDate today = LocalDate.now(BUSINESS_ZONE);
        LocalDate claimedOn = request.claimedOn() == null ? today : request.claimedOn();
        if (claimedOn.isAfter(today)) throw new BadRequestException("A claim cannot be dated in the future.");
        String note = trim(request.note());
        if (note != null && note.length() > NOTE_MAX) {
            throw new BadRequestException("Keep the note to " + NOTE_MAX + " characters.");
        }

        OffsetDateTime now = OffsetDateTime.now();
        AotgaClaim claim = claims.findBySpreadsheetIdAndTicketNo(props.getSpreadsheetId(), ticketNo)
                .orElseGet(() -> AotgaClaim.builder()
                        .spreadsheetId(props.getSpreadsheetId())
                        .ticketNo(ticketNo)
                        .createdAt(now)
                        .build());
        claim.setClaimedOn(claimedOn);
        claim.setNote(note);
        claim.setClaimedBy(user);
        claim.setUpdatedAt(now);
        claims.save(claim);
        return view();
    }

    /** Takes a claim back: the case is waiting for its claim again. */
    public View undo(String ticketNo, String user) {
        AotSheetProperties props = settings.effective();
        AotgaClaim claim = claims.findBySpreadsheetIdAndTicketNo(props.getSpreadsheetId(), trim(ticketNo))
                .orElseThrow(() -> new BadRequestException("No claim is recorded for " + ticketNo + "."));
        claim.setClaimedOn(null);
        claim.setNote(null);
        claim.setClaimedBy(user);
        claim.setUpdatedAt(OffsetDateTime.now());
        claims.save(claim);
        return view();
    }

    private static String trim(String value) {
        if (value == null) return null;
        String trimmed = value.trim();
        return trimmed.isEmpty() ? null : trimmed;
    }
}
