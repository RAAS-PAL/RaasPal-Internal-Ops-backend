package com.raaspal.robotrecommendation.kpi.service;

import com.raaspal.robotrecommendation.common.exception.BadRequestException;
import com.raaspal.robotrecommendation.kpi.config.KpiMondayProperties;
import com.raaspal.robotrecommendation.kpi.dto.KpiCaseMetricsResponse;
import com.raaspal.robotrecommendation.kpi.dto.KpiCaseMetricsResponse.Counts;
import com.raaspal.robotrecommendation.kpi.dto.KpiCaseMetricsResponse.MonthMetrics;
import com.raaspal.robotrecommendation.kpi.dto.KpiCaseMetricsResponse.Totals;
import com.raaspal.robotrecommendation.kpi.entity.CaseTicket;
import com.raaspal.robotrecommendation.kpi.entity.ServiceLine;
import com.raaspal.robotrecommendation.kpi.repository.CaseTicketRepository;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.time.YearMonth;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Computes the CM-case third of the RE KPI deck — Total CM Cases, SLA and
 * First Time Fix — from the synced {@link CaseTicket} rows, per month and per
 * service line.
 *
 * <p>The rules, stated once here and echoed in the response's
 * {@code definitions} so a board number can be traced back to them:
 * <ul>
 *   <li><b>Bucket</b> — a ticket belongs to the month it was opened in.
 *       Tickets with no open date cannot be placed and are not counted.</li>
 *   <li><b>SLA</b> — calendar days from open to close, compared with the
 *       board's limit: 3 days in greater Bangkok, 5 upcountry for Delivery,
 *       3 everywhere for Cleaning. Exactly the limit is still within. A
 *       ticket whose province is blank gets the upcountry (longer) limit — the
 *       lenient reading, because an internal KPI should not punish a missing
 *       field. Open tickets, and closed ones without a close date, are
 *       "unknown", never silently "within".</li>
 *   <li><b>Repeat / First Time Fix</b> — ticket T is a repeat when another
 *       ticket on the same service line names one of T's serials and opens
 *       within {@code repeat-window-days} after T's close date (or open date if
 *       T never closed). T then counts as not first-time-fixed. Two tickets for
 *       one serial on the same day count each other as repeats. A ticket naming
 *       no serial cannot be matched and counts as first-time-fixed, with the
 *       number of such tickets reported so the reader knows how soft the rate is.</li>
 * </ul>
 *
 * <p>These are this module's definitions. They are deliberately simple and
 * fully visible, and the response flags them {@code provisional} until the RE
 * team confirms them against the sheet the deck was built from
 * ("Case_FTFR_SLA Jan–Jun 2026").
 */
@Service
public class KpiCaseMetricsService {

    /** The widest range one request may ask for; the console asks for six months. */
    public static final int MAX_MONTHS = 24;

    private final CaseTicketRepository ticketRepository;
    private final KpiMondayProperties properties;
    private final Set<String> metroProvinces;

    public KpiCaseMetricsService(
            CaseTicketRepository ticketRepository,
            KpiMondayProperties properties,
            @Value("${app.casereport.metro-provinces:}") List<String> metroProvinces) {
        this.ticketRepository = ticketRepository;
        this.properties = properties;
        this.metroProvinces = metroProvinces.stream()
                .map(KpiCaseMetricsService::normaliseProvince)
                .filter(p -> !p.isEmpty())
                .collect(Collectors.toSet());
    }

    @Transactional(readOnly = true)
    public KpiCaseMetricsResponse monthly(YearMonth from, YearMonth to) {
        if (to.isBefore(from)) {
            throw new BadRequestException("'to' must not be before 'from'");
        }
        long span = ChronoUnit.MONTHS.between(from, to) + 1;
        if (span > MAX_MONTHS) {
            throw new BadRequestException("The range must not exceed " + MAX_MONTHS + " months");
        }

        int window = properties.getRepeatWindowDays();
        LocalDate start = from.atDay(1);
        LocalDate end = to.atEndOfMonth();
        // Read past the end by one window so a ticket opened in the last month can
        // still see the repeat that followed it.
        List<CaseTicket> tickets = ticketRepository.findAllByPresentTrueAndOpenDateBetween(start, end.plusDays(window));

        Map<ServiceLine, Map<String, List<CaseTicket>>> bySerial = indexBySerial(tickets);

        Map<YearMonth, Bucket> buckets = new LinkedHashMap<>();
        for (YearMonth month = from; !month.isAfter(to); month = month.plusMonths(1)) {
            buckets.put(month, new Bucket());
        }
        Bucket totals = new Bucket();

        long counted = 0;
        for (CaseTicket ticket : tickets) {
            if (ticket.getOpenDate().isAfter(end)) {
                continue; // look-ahead only
            }
            counted++;
            Bucket bucket = buckets.get(YearMonth.from(ticket.getOpenDate()));
            Classification c = classify(ticket, bySerial, window);
            bucket.add(ticket.getServiceLine(), c);
            totals.add(ticket.getServiceLine(), c);
        }

        List<MonthMetrics> months = new ArrayList<>();
        buckets.forEach((month, bucket) -> months.add(new MonthMetrics(
                month.toString(), bucket.all.toCounts(), bucket.cleaning.toCounts(), bucket.delivery.toCounts())));

        return new KpiCaseMetricsResponse(
                from.toString(),
                to.toString(),
                months,
                new Totals(totals.all.toCounts(), totals.cleaning.toCounts(), totals.delivery.toCounts()),
                counted,
                ticketRepository.findLastSyncedAt().orElse(null),
                window,
                true,
                definitions(window));
    }

    /** Which side of each rule a ticket falls on. */
    private record Classification(boolean closed, SlaOutcome sla, boolean hasSerial, boolean repeated) {
    }

    private enum SlaOutcome { WITHIN, OVER, UNKNOWN }

    private Classification classify(CaseTicket ticket, Map<ServiceLine, Map<String, List<CaseTicket>>> bySerial,
                                    int window) {
        SlaOutcome sla = SlaOutcome.UNKNOWN;
        if (ticket.getCloseDate() != null) {
            long days = ChronoUnit.DAYS.between(ticket.getOpenDate(), ticket.getCloseDate());
            sla = days <= slaDaysFor(ticket) ? SlaOutcome.WITHIN : SlaOutcome.OVER;
        }

        List<String> serials = CaseTicketMapper.splitSerials(ticket.getSerialsNormalised());
        boolean repeated = false;
        if (!serials.isEmpty()) {
            LocalDate anchor = ticket.getCloseDate() != null ? ticket.getCloseDate() : ticket.getOpenDate();
            LocalDate windowEnd = anchor.plusDays(window);
            Map<String, List<CaseTicket>> lineIndex = bySerial.getOrDefault(ticket.getServiceLine(), Map.of());
            repeated = serials.stream()
                    .flatMap(serial -> lineIndex.getOrDefault(serial, List.of()).stream())
                    .anyMatch(other -> other != ticket
                            && !other.getOpenDate().isBefore(ticket.getOpenDate())
                            && !other.getOpenDate().isBefore(anchor)
                            && !other.getOpenDate().isAfter(windowEnd));
        }
        return new Classification(ticket.isClosed(), sla, !serials.isEmpty(), repeated);
    }

    /**
     * The SLA limit for a ticket: from its board's config, metro or upcountry by
     * province. A board not in the config (a spreadsheet import, say) gets the
     * Delivery defaults, which are the more lenient of the two.
     */
    private int slaDaysFor(CaseTicket ticket) {
        KpiMondayProperties.Board board = properties.board(ticket.getSourceBoardId()).orElse(null);
        int metro = board == null ? 3 : board.getSlaDaysMetro();
        int upcountry = board == null ? 5 : board.getSlaDaysUpcountry();
        return isMetro(ticket.getProvinceRaw()) ? metro : upcountry;
    }

    private boolean isMetro(String province) {
        return province != null && metroProvinces.contains(normaliseProvince(province));
    }

    private static String normaliseProvince(String value) {
        return value == null ? "" : value.strip().toLowerCase(Locale.ROOT);
    }

    private static Map<ServiceLine, Map<String, List<CaseTicket>>> indexBySerial(List<CaseTicket> tickets) {
        Map<ServiceLine, Map<String, List<CaseTicket>>> index = new HashMap<>();
        for (CaseTicket ticket : tickets) {
            Map<String, List<CaseTicket>> lineIndex = index.computeIfAbsent(ticket.getServiceLine(), k -> new HashMap<>());
            for (String serial : CaseTicketMapper.splitSerials(ticket.getSerialsNormalised())) {
                lineIndex.computeIfAbsent(serial, k -> new ArrayList<>()).add(ticket);
            }
        }
        return index;
    }

    private static Map<String, String> definitions(int window) {
        Map<String, String> d = new LinkedHashMap<>();
        d.put("total", "Tickets opened in the month (by the board's open-date column); tickets with no open date are not counted.");
        d.put("closed", "Close date set, or status listed as finished in the board config.");
        d.put("sla", "Calendar days from open to close vs the board's limit (Cleaning 3; Delivery 3 metro / 5 upcountry). "
                + "Exactly the limit is within. Blank province = upcountry limit. Open tickets and closed tickets without a close date are unknown.");
        d.put("repeat", "Another ticket on the same service line names one of this ticket's serials and opens within "
                + window + " days after this ticket's close date (open date if never closed). Same-day pairs count each other.");
        d.put("firstTimeFix", "Not a repeat. Tickets naming no serial cannot be matched and are counted here; see withoutSerial.");
        return d;
    }

    /** Mutable counters for one bucket, split three ways. */
    private static final class Bucket {
        final Counter all = new Counter();
        final Counter cleaning = new Counter();
        final Counter delivery = new Counter();

        void add(ServiceLine line, Classification c) {
            all.add(c);
            (line == ServiceLine.CLEANING ? cleaning : delivery).add(c);
        }
    }

    private static final class Counter {
        int total;
        int closed;
        int slaWithin;
        int slaOver;
        int slaUnknown;
        int firstTimeFix;
        int repeat;
        int withoutSerial;

        void add(Classification c) {
            total++;
            if (c.closed()) {
                closed++;
            }
            switch (c.sla()) {
                case WITHIN -> slaWithin++;
                case OVER -> slaOver++;
                case UNKNOWN -> slaUnknown++;
            }
            if (c.repeated()) {
                repeat++;
            } else {
                firstTimeFix++;
            }
            if (!c.hasSerial()) {
                withoutSerial++;
            }
        }

        Counts toCounts() {
            return new Counts(total, closed, slaWithin, slaOver, slaUnknown, firstTimeFix, repeat, withoutSerial,
                    rate(slaWithin, slaWithin + slaOver), rate(firstTimeFix, total));
        }

        /** Percentage to one decimal, or null when there is nothing to divide by. */
        private static Double rate(int numerator, int denominator) {
            if (denominator == 0) {
                return null;
            }
            return Math.round(numerator * 1000.0 / denominator) / 10.0;
        }
    }
}
