package com.raaspal.robotrecommendation.kpi.service;

import com.raaspal.robotrecommendation.common.exception.BadRequestException;
import com.raaspal.robotrecommendation.kpi.config.KpiMondayProperties;
import com.raaspal.robotrecommendation.kpi.dto.KpiCaseMetricsResponse;
import com.raaspal.robotrecommendation.kpi.dto.KpiCaseMetricsResponse.CmCounts;
import com.raaspal.robotrecommendation.kpi.dto.KpiCaseMetricsResponse.InstallCounts;
import com.raaspal.robotrecommendation.kpi.dto.KpiCaseMetricsResponse.MonthMetrics;
import com.raaspal.robotrecommendation.kpi.dto.KpiCaseMetricsResponse.Segment;
import com.raaspal.robotrecommendation.kpi.entity.KpiCaseTicket;
import com.raaspal.robotrecommendation.kpi.entity.ServiceLine;
import com.raaspal.robotrecommendation.kpi.entity.TicketType;
import com.raaspal.robotrecommendation.kpi.repository.KpiCaseTicketRepository;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.time.YearMonth;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.HashSet;

/**
 * The RE KPI numbers, computed from the synced tickets. The formulas are the RE
 * team's, given 2026-09-08, and are implemented literally:
 *
 * <ul>
 *   <li><b>1st Time Install</b> — from the installation ticket's TimeLine (the
 *       <em>later</em> date, i.e. when the work finished), look forward
 *       {@code install-follow-up-days} (30). If any CM names the same serial in
 *       that window, that installation scores 0. Otherwise 1.</li>
 *   <li><b>First Time Fix</b> — after a CM, another CM naming the same serial
 *       within {@code repeat-window-days} (14) scores 0. The <em>later</em>
 *       ticket is not penalised for existing; the earlier one is.</li>
 *   <li><b>SLA</b> — the case was checked within {@code sla-days} (7) of being
 *       reported: the board's RE Action date minus its Open Date. Exactly the
 *       limit is still within. Neither ticket board has a close-date column, so
 *       this deliberately measures time-to-first-action, not time-to-close.</li>
 * </ul>
 *
 * <p>A ticket is bucketed into the month of the date its own KPI is keyed on:
 * installations by install date, CMs by open date.
 *
 * <p>Both windows join on <b>serial number</b> alone, not on service line. A
 * serial identifies one physical robot, so a CM for it is a CM for it whichever
 * board recorded it — and that keeps the figures right even if a board's line
 * mapping is wrong. A ticket naming no serial cannot be matched at all; those
 * are counted as successes and reported separately as {@code withoutSerial}, so
 * the reader can see how much of the rate rests on unmatched rows.
 */
@Slf4j
@Service
public class KpiCaseMetricsService {

    /** The widest range one request may ask for; the console asks for six months. */
    public static final int MAX_MONTHS = 24;

    private final KpiCaseTicketRepository ticketRepository;
    private final KpiMondayProperties properties;

    public KpiCaseMetricsService(KpiCaseTicketRepository ticketRepository, KpiMondayProperties properties) {
        this.ticketRepository = ticketRepository;
        this.properties = properties;
    }

    @Transactional(readOnly = true)
    public KpiCaseMetricsResponse monthly(YearMonth from, YearMonth to) {
        if (to.isBefore(from)) {
            throw new BadRequestException("'to' must not be before 'from'");
        }
        if (ChronoUnit.MONTHS.between(from, to) + 1 > MAX_MONTHS) {
            throw new BadRequestException("The range must not exceed " + MAX_MONTHS + " months");
        }

        int repeatWindow = properties.getRepeatWindowDays();
        int installWindow = properties.getInstallFollowUpDays();
        LocalDate start = from.atDay(1);
        LocalDate end = to.atEndOfMonth();

        // Read past the end by the longer window, so a ticket in the final month
        // can still see the follow-up that disqualifies it.
        LocalDate lookAheadTo = end.plusDays(Math.max(repeatWindow, installWindow));
        List<KpiCaseTicket> tickets = ticketRepository.findAllPresentInWindow(start, lookAheadTo);

        // Every CM in the window, indexed by serial: the follow-up both formulas hunt for.
        Map<String, List<KpiCaseTicket>> cmBySerial = new HashMap<>();
        for (KpiCaseTicket ticket : tickets) {
            if (ticket.getTicketType() == TicketType.CM && ticket.getOpenDate() != null) {
                for (String serial : CaseTicketMapper.splitSerials(ticket.getSerialsNormalised())) {
                    cmBySerial.computeIfAbsent(serial, k -> new ArrayList<>()).add(ticket);
                }
            }
        }

        // A serial seen on a single-line CM board says what kind of robot it is,
        // which is how an installation gets classified when its own board does not
        // say. Built from every CM ticket, not just this window's.
        Map<String, ServiceLine> lineBySerial = serialServiceLines();

        Map<YearMonth, Bucket> buckets = new LinkedHashMap<>();
        for (YearMonth month = from; !month.isAfter(to); month = month.plusMonths(1)) {
            buckets.put(month, new Bucket());
        }
        Bucket totals = new Bucket();

        long counted = 0;
        long unclassified = 0;
        long excludedByCategory = 0;
        for (KpiCaseTicket ticket : tickets) {
            LocalDate keyDate = keyDate(ticket);
            if (keyDate == null || keyDate.isBefore(start) || keyDate.isAfter(end)) {
                continue; // outside the range, or unusable — look-ahead rows land here
            }
            if (!countsCategory(ticket)) {
                excludedByCategory++;
                continue; // the team's work, but not this KPI's case
            }
            counted++;
            ServiceLine line = resolveLine(ticket, lineBySerial);
            if (line == null) {
                unclassified++;
            }
            Bucket bucket = buckets.get(YearMonth.from(keyDate));
            if (ticket.getTicketType() == TicketType.INSTALLATION) {
                boolean success = !hasFollowUpCm(ticket, keyDate, installWindow, cmBySerial);
                bucket.addInstall(ticket, line, success);
                totals.addInstall(ticket, line, success);
            } else {
                boolean fixedFirstTime = !hasFollowUpCm(ticket, keyDate, repeatWindow, cmBySerial);
                SlaOutcome sla = slaOutcome(ticket);
                bucket.addCm(ticket, line, fixedFirstTime, sla);
                totals.addCm(ticket, line, fixedFirstTime, sla);
            }
        }

        List<MonthMetrics> months = new ArrayList<>();
        buckets.forEach((month, bucket) -> months.add(bucket.toMonth(month.toString())));

        return new KpiCaseMetricsResponse(
                from.toString(),
                to.toString(),
                months,
                totals.toSegments(),
                counted,
                unclassified,
                excludedByCategory,
                ticketRepository.findLastSyncedAt().orElse(null),
                repeatWindow,
                installWindow,
                uniformSlaDays(),
                true,
                definitions(repeatWindow, installWindow, uniformSlaDays()));
    }

    /**
     * What kind of robot a ticket concerns: the line the sync already resolved,
     * or — for an installation board that does not say — the line of a CM ticket
     * naming the same serial. Null when neither answers, which is reported rather
     * than guessed.
     */
    private static ServiceLine resolveLine(KpiCaseTicket ticket, Map<String, ServiceLine> lineBySerial) {
        if (ticket.getServiceLine() != null) {
            return ticket.getServiceLine();
        }
        for (String serial : CaseTicketMapper.splitSerials(ticket.getSerialsNormalised())) {
            ServiceLine known = lineBySerial.get(serial);
            if (known != null) {
                return known;
            }
        }
        return null;
    }

    /**
     * Serial to service line, from every CM ticket that carries both.
     *
     * <p>A serial claimed by both boards is dropped rather than resolved to
     * whichever row was read first: one robot cannot be both, so a collision means
     * the data is wrong, and an arbitrary winner would hide that in a board figure.
     */
    private Map<String, ServiceLine> serialServiceLines() {
        Map<String, ServiceLine> bySerial = new HashMap<>();
        Set<String> conflicting = new HashSet<>();
        for (Object[] row : ticketRepository.findSerialServiceLines()) {
            ServiceLine line = (ServiceLine) row[1];
            for (String serial : CaseTicketMapper.splitSerials((String) row[0])) {
                ServiceLine seen = bySerial.putIfAbsent(serial, line);
                if (seen != null && seen != line) {
                    conflicting.add(serial);
                }
            }
        }
        conflicting.forEach(bySerial::remove);
        if (!conflicting.isEmpty()) {
            log.warn("{} serial(s) appear on both the cleaning and delivery boards; "
                    + "they cannot classify an installation and are ignored", conflicting.size());
        }
        return bySerial;
    }

    /**
     * Whether the row is a case this KPI counts. A board that maps no category, or
     * lists no included values, counts every row. Note the follow-up search in
     * {@link #hasFollowUpCm} deliberately does NOT apply this: a parts-shipping
     * row for the same serial is still evidence that the robot came back.
     */
    private boolean countsCategory(KpiCaseTicket ticket) {
        return properties.board(ticket.getSourceBoardId())
                .map(board -> board.countsCategory(ticket.getCategory()))
                .orElse(true);
    }

    /** The date a ticket's KPI is keyed on: install finished, or case reported. */
    private static LocalDate keyDate(KpiCaseTicket ticket) {
        return ticket.getTicketType() == TicketType.INSTALLATION ? ticket.getInstallDate() : ticket.getOpenDate();
    }

    /**
     * Whether a CM for one of this ticket's serials opens in
     * {@code (anchor, anchor + windowDays]}.
     *
     * <p>The window is open at the start: a CM opening on the anchor date itself
     * is this ticket, or the same visit recorded twice, not evidence that the fix
     * failed. It is closed at the end, so a follow-up exactly on the limit still
     * counts against it.
     */
    private static boolean hasFollowUpCm(KpiCaseTicket ticket, LocalDate anchor, int windowDays,
                                         Map<String, List<KpiCaseTicket>> cmBySerial) {
        LocalDate limit = anchor.plusDays(windowDays);
        for (String serial : CaseTicketMapper.splitSerials(ticket.getSerialsNormalised())) {
            for (KpiCaseTicket other : cmBySerial.getOrDefault(serial, List.of())) {
                if (other == ticket) {
                    continue;
                }
                LocalDate opened = other.getOpenDate();
                if (opened.isAfter(anchor) && !opened.isAfter(limit)) {
                    return true;
                }
            }
        }
        return false;
    }

    private enum SlaOutcome { WITHIN, OVER, UNKNOWN }

    /**
     * Checked within the board's SLA days of being reported. No action date means
     * unknown, never a breach: an unrecorded action and a late action are
     * different claims and only one of them is evidence.
     */
    private SlaOutcome slaOutcome(KpiCaseTicket ticket) {
        if (ticket.getOpenDate() == null || ticket.getActionDate() == null) {
            return SlaOutcome.UNKNOWN;
        }
        int slaDays = properties.board(ticket.getSourceBoardId())
                .map(KpiMondayProperties.Board::getSlaDays)
                .orElse(7);
        return ChronoUnit.DAYS.between(ticket.getOpenDate(), ticket.getActionDate()) <= slaDays
                ? SlaOutcome.WITHIN
                : SlaOutcome.OVER;
    }

    /**
     * The SLA threshold, when every CM board agrees on one. Null when they differ,
     * because a fleet-wide figure would then be a fiction — the caller says
     * "the board's SLA days" instead of naming a number that is only sometimes true.
     */
    private Integer uniformSlaDays() {
        Set<Integer> distinct = properties.getBoards().stream()
                .filter(board -> board.getTicketType() == TicketType.CM)
                .map(KpiMondayProperties.Board::getSlaDays)
                .collect(java.util.stream.Collectors.toSet());
        return distinct.size() == 1 ? distinct.iterator().next() : null;
    }

    private static Map<String, String> definitions(int repeatWindow, int installWindow, Integer slaDays) {
        Map<String, String> d = new LinkedHashMap<>();
        d.put("firstTimeInstall", "An installation scores 1 when NO corrective-maintenance ticket names the same serial "
                + "within " + installWindow + " days after the installation's TimeLine end date; 0 when one does.");
        d.put("firstTimeFix", "A CM scores 1 when NO later CM names the same serial within " + repeatWindow
                + " days of it being reported; 0 when one does.");
        d.put("sla", "A CM case was checked within "
                + (slaDays == null ? "the board's SLA days" : slaDays + " days")
                + " of being reported: RE Action date minus Open Date. Exactly the limit is within. "
                + "No action date recorded = unknown, not a breach. Neither board has a close date, "
                + "so this is time-to-first-action, not time-to-close. SLA is measured on CM only.");
        d.put("bucketing", "Installations are counted in the month their TimeLine ends; CMs in the month they were reported.");
        d.put("matching", "Both windows join on serial number alone, across boards. Tickets naming no serial "
                + "cannot be matched and are counted as successes; see withoutSerial.");
        d.put("category", "Each board may name a category column (Job Type, Type of case) and the values that count "
                + "as a KPI case. Rows outside that list are synced and archived but not counted here; see "
                + "excludedByCategory. A follow-up CM is still a follow-up whatever its category.");
        d.put("split", "An installation board does not say which kind of robot a ticket concerns, so the serial is "
                + "looked up among CM tickets: the cleaning and delivery boards are single-line, so a serial seen on "
                + "one of them identifies the robot. A ticket that still cannot be placed counts in the fleet total "
                + "but in neither column; see unclassifiedTickets.");
        return d;
    }

    /** Mutable counters for one bucket, split by ticket type and service line. */
    private static final class Bucket {
        final Counter all = new Counter();
        final Counter cleaning = new Counter();
        final Counter delivery = new Counter();

        void addInstall(KpiCaseTicket ticket, ServiceLine line, boolean success) {
            for (Counter c : forLine(line)) {
                c.addInstall(success, ticket.getSerialsNormalised() == null);
            }
        }

        void addCm(KpiCaseTicket ticket, ServiceLine line, boolean fixedFirstTime, SlaOutcome sla) {
            for (Counter c : forLine(line)) {
                c.addCm(fixedFirstTime, sla, ticket.getSerialsNormalised() == null);
            }
        }

        /**
         * The counters a ticket belongs to. A ticket with no service line counts in
         * the fleet total only — it is not forced into one side of the split, which
         * would put an invisible error on a board slide.
         */
        private Counter[] forLine(ServiceLine line) {
            if (line == null) {
                return new Counter[]{all};
            }
            return new Counter[]{all, line == ServiceLine.CLEANING ? cleaning : delivery};
        }

        MonthMetrics toMonth(String month) {
            return new MonthMetrics(month, all.toSegment(), cleaning.toSegment(), delivery.toSegment());
        }

        KpiCaseMetricsResponse.Totals toSegments() {
            return new KpiCaseMetricsResponse.Totals(all.toSegment(), cleaning.toSegment(), delivery.toSegment());
        }
    }

    private static final class Counter {
        int installTotal;
        int installFirstTime;
        int installFollowedByCm;
        int installWithoutSerial;

        int cmTotal;
        int cmFirstTimeFix;
        int cmRepeat;
        int cmWithoutSerial;
        int slaWithin;
        int slaOver;
        int slaUnknown;

        void addInstall(boolean success, boolean noSerial) {
            installTotal++;
            if (success) {
                installFirstTime++;
            } else {
                installFollowedByCm++;
            }
            if (noSerial) {
                installWithoutSerial++;
            }
        }

        void addCm(boolean fixedFirstTime, SlaOutcome sla, boolean noSerial) {
            cmTotal++;
            if (fixedFirstTime) {
                cmFirstTimeFix++;
            } else {
                cmRepeat++;
            }
            switch (sla) {
                case WITHIN -> slaWithin++;
                case OVER -> slaOver++;
                case UNKNOWN -> slaUnknown++;
            }
            if (noSerial) {
                cmWithoutSerial++;
            }
        }

        Segment toSegment() {
            return new Segment(
                    new InstallCounts(installTotal, installFirstTime, installFollowedByCm, installWithoutSerial,
                            rate(installFirstTime, installTotal)),
                    new CmCounts(cmTotal, cmFirstTimeFix, cmRepeat, cmWithoutSerial, slaWithin, slaOver, slaUnknown,
                            rate(cmFirstTimeFix, cmTotal), rate(slaWithin, slaWithin + slaOver)));
        }

        /** Percentage to one decimal, or null when there is nothing to divide by. */
        private static Double rate(int numerator, int denominator) {
            return denominator == 0 ? null : Math.round(numerator * 1000.0 / denominator) / 10.0;
        }
    }
}
