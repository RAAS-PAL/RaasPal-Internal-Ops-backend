package com.raaspal.robotrecommendation.kpi.service;

import com.raaspal.robotrecommendation.common.exception.BadRequestException;
import com.raaspal.robotrecommendation.kpi.config.KpiMondayProperties;
import com.raaspal.robotrecommendation.kpi.dto.KpiCaseMetricsResponse;
import com.raaspal.robotrecommendation.kpi.dto.KpiCaseMetricsResponse.CmCounts;
import com.raaspal.robotrecommendation.kpi.dto.KpiCaseMetricsResponse.InstallCounts;
import com.raaspal.robotrecommendation.kpi.dto.KpiCaseMetricsResponse.MonthMetrics;
import com.raaspal.robotrecommendation.kpi.dto.KpiCaseMetricsResponse.Segment;
import com.raaspal.robotrecommendation.kpi.entity.CaseTicket;
import com.raaspal.robotrecommendation.kpi.entity.ServiceLine;
import com.raaspal.robotrecommendation.kpi.entity.TicketType;
import com.raaspal.robotrecommendation.kpi.repository.CaseTicketRepository;
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
@Service
public class KpiCaseMetricsService {

    /** The widest range one request may ask for; the console asks for six months. */
    public static final int MAX_MONTHS = 24;

    private final CaseTicketRepository ticketRepository;
    private final KpiMondayProperties properties;

    public KpiCaseMetricsService(CaseTicketRepository ticketRepository, KpiMondayProperties properties) {
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
        List<CaseTicket> tickets = ticketRepository.findAllPresentInWindow(start, lookAheadTo);

        // Every CM in the window, indexed by serial: the follow-up both formulas hunt for.
        Map<String, List<CaseTicket>> cmBySerial = new HashMap<>();
        for (CaseTicket ticket : tickets) {
            if (ticket.getTicketType() == TicketType.CM && ticket.getOpenDate() != null) {
                for (String serial : CaseTicketMapper.splitSerials(ticket.getSerialsNormalised())) {
                    cmBySerial.computeIfAbsent(serial, k -> new ArrayList<>()).add(ticket);
                }
            }
        }

        Map<YearMonth, Bucket> buckets = new LinkedHashMap<>();
        for (YearMonth month = from; !month.isAfter(to); month = month.plusMonths(1)) {
            buckets.put(month, new Bucket());
        }
        Bucket totals = new Bucket();

        long counted = 0;
        for (CaseTicket ticket : tickets) {
            LocalDate keyDate = keyDate(ticket);
            if (keyDate == null || keyDate.isBefore(start) || keyDate.isAfter(end)) {
                continue; // outside the range, or unusable — look-ahead rows land here
            }
            counted++;
            Bucket bucket = buckets.get(YearMonth.from(keyDate));
            if (ticket.getTicketType() == TicketType.INSTALLATION) {
                boolean success = !hasFollowUpCm(ticket, keyDate, installWindow, cmBySerial);
                bucket.addInstall(ticket, success);
                totals.addInstall(ticket, success);
            } else {
                boolean fixedFirstTime = !hasFollowUpCm(ticket, keyDate, repeatWindow, cmBySerial);
                SlaOutcome sla = slaOutcome(ticket);
                bucket.addCm(ticket, fixedFirstTime, sla);
                totals.addCm(ticket, fixedFirstTime, sla);
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
                ticketRepository.findLastSyncedAt().orElse(null),
                repeatWindow,
                installWindow,
                true,
                definitions(repeatWindow, installWindow));
    }

    /** The date a ticket's KPI is keyed on: install finished, or case reported. */
    private static LocalDate keyDate(CaseTicket ticket) {
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
    private static boolean hasFollowUpCm(CaseTicket ticket, LocalDate anchor, int windowDays,
                                         Map<String, List<CaseTicket>> cmBySerial) {
        LocalDate limit = anchor.plusDays(windowDays);
        for (String serial : CaseTicketMapper.splitSerials(ticket.getSerialsNormalised())) {
            for (CaseTicket other : cmBySerial.getOrDefault(serial, List.of())) {
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
    private SlaOutcome slaOutcome(CaseTicket ticket) {
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

    private static Map<String, String> definitions(int repeatWindow, int installWindow) {
        Map<String, String> d = new LinkedHashMap<>();
        d.put("firstTimeInstall", "An installation scores 1 when NO corrective-maintenance ticket names the same serial "
                + "within " + installWindow + " days after the installation's TimeLine end date; 0 when one does.");
        d.put("firstTimeFix", "A CM scores 1 when NO later CM names the same serial within " + repeatWindow
                + " days of it being reported; 0 when one does.");
        d.put("sla", "The case was checked within the board's SLA days (7) of being reported: "
                + "RE Action date minus Open Date. Exactly the limit is within. "
                + "No action date recorded = unknown, not a breach. Neither board has a close date, "
                + "so this is time-to-first-action, not time-to-close.");
        d.put("bucketing", "Installations are counted in the month their TimeLine ends; CMs in the month they were reported.");
        d.put("matching", "Both windows join on serial number alone, across boards. Tickets naming no serial "
                + "cannot be matched and are counted as successes; see withoutSerial.");
        return d;
    }

    /** Mutable counters for one bucket, split by ticket type and service line. */
    private static final class Bucket {
        final Counter all = new Counter();
        final Counter cleaning = new Counter();
        final Counter delivery = new Counter();

        void addInstall(CaseTicket ticket, boolean success) {
            for (Counter c : forLine(ticket.getServiceLine())) {
                c.addInstall(success, ticket.getSerialsNormalised() == null);
            }
        }

        void addCm(CaseTicket ticket, boolean fixedFirstTime, SlaOutcome sla) {
            for (Counter c : forLine(ticket.getServiceLine())) {
                c.addCm(fixedFirstTime, sla, ticket.getSerialsNormalised() == null);
            }
        }

        private Counter[] forLine(ServiceLine line) {
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
