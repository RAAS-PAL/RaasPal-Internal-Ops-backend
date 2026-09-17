package com.raaspal.robotrecommendation.casereport.brand;

import com.raaspal.robotrecommendation.casereport.brand.dto.BrandTicket;
import com.raaspal.robotrecommendation.casereport.brand.dto.BrandTicketSummary;
import com.raaspal.robotrecommendation.casereport.brand.dto.BrandTicketSummary.*;
import org.springframework.stereotype.Service;

import java.time.LocalDate;
import java.time.YearMonth;
import java.util.*;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * Turns a brand's ticket list into the numbers the page draws.
 *
 * <p>Pure: every figure is computed from the list it is given, so a test can hand it
 * six tickets and check the maths. The rules are the RE team's own (2026-09-08): an RE
 * Action within 7 days of Open Date is on time, and a second ticket on the same serial
 * within 14 days is a repeat rather than a new fault.
 */
@Service
public class BrandTicketAnalyticsService {

    static final int SLA_DAYS = 7;
    static final int REPEAT_DAYS = 14;
    private static final int TOP_N = 8;

    /** Aging buckets for open tickets, in days since Open Date. */
    private static final int[][] AGING = {{0, 7}, {8, 14}, {15, 30}, {31, Integer.MAX_VALUE}};
    private static final String[] AGING_LABELS = {"0-7", "8-14", "15-30", "31+"};

    public BrandTicketSummary summarise(BrandTicketProperties.Brand brand,
                                       List<BrandTicket> all,
                                       LocalDate from,
                                       LocalDate to,
                                       LocalDate today) {

        List<BrandTicket> inRange = all.stream()
                .filter(t -> inRange(BrandTicketQueryService.ticketDate(t), from, to))
                .toList();

        List<BrandTicket> openNow = all.stream().filter(BrandTicket::open).toList();

        YearMonth thisMonth = YearMonth.from(today);
        int thisMonthCount = countInMonth(all, thisMonth);
        int lastMonthCount = countInMonth(all, thisMonth.minusMonths(1));

        return BrandTicketSummary.builder()
                .brand(brand.getKey())
                .label(brand.getLabel())
                .boardId(brand.getBoardId())
                .from(from)
                .to(to)
                .lastSyncedAt(all.stream().map(BrandTicket::lastSyncedAt)
                        .filter(Objects::nonNull).max(Comparator.naturalOrder()).orElse(null))
                .totals(Totals.builder()
                        .tickets(inRange.size())
                        .open((int) inRange.stream().filter(BrandTicket::open).count())
                        .done((int) inRange.stream().filter(t -> !t.open()).count())
                        .robots((int) inRange.stream().map(BrandTicket::serial).filter(Objects::nonNull).distinct().count())
                        .sites((int) inRange.stream().map(BrandTicket::project).filter(Objects::nonNull).distinct().count())
                        .build())
                .kpis(Kpis.builder()
                        .openNow(openNow.size())
                        .oldestOpenDays(openNow.stream().map(BrandTicket::ageDays)
                                .filter(Objects::nonNull).max(Comparator.naturalOrder()).orElse(null))
                        .thisMonth(thisMonthCount)
                        .lastMonth(lastMonthCount)
                        .monthDeltaPct(lastMonthCount == 0 ? null
                                : round1(100.0 * (thisMonthCount - lastMonthCount) / lastMonthCount))
                        .medianDaysToAction(median(inRange.stream().map(BrandTicket::daysToAction)
                                .filter(Objects::nonNull).toList()))
                        .actionSample((int) inRange.stream().map(BrandTicket::daysToAction).filter(Objects::nonNull).count())
                        .slaWithin7Pct(share(inRange.stream().map(BrandTicket::daysToAction).filter(Objects::nonNull).toList(),
                                d -> d <= SLA_DAYS))
                        .repeatRatePct(repeatRate(inRange, all))
                        .repeatSample((int) inRange.stream().filter(t -> t.serial() != null).count())
                        .build())
                .monthly(monthly(inRange, from, to))
                .statuses(countBy(inRange, BrandTicket::status))
                .rootCauses(countBy(inRange, BrandTicket::rootCause))
                .models(countBy(inRange, BrandTicket::model))
                .reOwners(countBy(inRange, BrandTicket::reOwner))
                .aging(aging(openNow))
                .topSites(topSites(inRange))
                .repeatRobots(repeatRobots(inRange))
                .definitions(new Definitions(
                        "Not in a Done group and status is not Done",
                        "RE Action within " + SLA_DAYS + " days of Open Date",
                        "Another ticket on the same serial within " + REPEAT_DAYS + " days",
                        "Open Date, or the day the ticket was first synced when Open Date is blank"))
                .build();
    }

    private static boolean inRange(LocalDate d, LocalDate from, LocalDate to) {
        if (d == null) return false;
        return (from == null || !d.isBefore(from)) && (to == null || !d.isAfter(to));
    }

    private static int countInMonth(List<BrandTicket> all, YearMonth month) {
        return (int) all.stream()
                .map(BrandTicketQueryService::ticketDate)
                .filter(d -> d != null && YearMonth.from(d).equals(month))
                .count();
    }

    /**
     * One point per calendar month across the range, zero-filled, so a quiet month is
     * a gap on the chart and not a missing bar.
     */
    private static List<MonthPoint> monthly(List<BrandTicket> inRange, LocalDate from, LocalDate to) {
        if (inRange.isEmpty()) return List.of();

        LocalDate earliest = inRange.stream().map(BrandTicketQueryService::ticketDate).min(Comparator.naturalOrder()).get();
        LocalDate latest = inRange.stream().map(BrandTicketQueryService::ticketDate).max(Comparator.naturalOrder()).get();
        YearMonth start = YearMonth.from(from != null ? from : earliest);
        YearMonth end = YearMonth.from(to != null ? to : latest);

        Map<YearMonth, List<BrandTicket>> byMonth = inRange.stream()
                .collect(Collectors.groupingBy(t -> YearMonth.from(BrandTicketQueryService.ticketDate(t))));

        List<MonthPoint> points = new ArrayList<>();
        for (YearMonth m = start; !m.isAfter(end); m = m.plusMonths(1)) {
            List<BrandTicket> month = byMonth.getOrDefault(m, List.of());
            int open = (int) month.stream().filter(BrandTicket::open).count();
            points.add(new MonthPoint(m.toString(), month.size(), open, month.size() - open));
        }
        return points;
    }

    private static List<Count> countBy(List<BrandTicket> tickets, Function<BrandTicket, String> key) {
        Map<String, Long> counts = tickets.stream()
                .collect(Collectors.groupingBy(t -> {
                    String k = key.apply(t);
                    return k == null || k.isBlank() ? "(blank)" : k.trim();
                }, Collectors.counting()));
        return counts.entrySet().stream()
                .sorted(Map.Entry.<String, Long>comparingByValue().reversed()
                        .thenComparing(Map.Entry.comparingByKey()))
                .map(e -> new Count(e.getKey(), e.getValue().intValue()))
                .toList();
    }

    private static List<Count> aging(List<BrandTicket> openNow) {
        List<Count> buckets = new ArrayList<>();
        for (int i = 0; i < AGING.length; i++) {
            int lo = AGING[i][0], hi = AGING[i][1];
            int n = (int) openNow.stream().map(BrandTicket::ageDays)
                    .filter(a -> a != null && a >= lo && a <= hi).count();
            buckets.add(new Count(AGING_LABELS[i], n));
        }
        return buckets;
    }

    private static List<SiteCount> topSites(List<BrandTicket> tickets) {
        Map<String, List<BrandTicket>> bySite = tickets.stream()
                .filter(t -> t.project() != null && !t.project().isBlank())
                .collect(Collectors.groupingBy(t -> t.project().trim()));
        return bySite.entrySet().stream()
                .map(e -> new SiteCount(e.getKey(), e.getValue().size(),
                        (int) e.getValue().stream().filter(BrandTicket::open).count()))
                .sorted(Comparator.comparingInt(SiteCount::count).reversed().thenComparing(SiteCount::label))
                .limit(TOP_N)
                .toList();
    }

    /** Robots with more than one ticket in the range, most-ticketed first. */
    private static List<RobotCount> repeatRobots(List<BrandTicket> tickets) {
        Map<String, List<BrandTicket>> bySerial = tickets.stream()
                .filter(t -> t.serial() != null)
                .collect(Collectors.groupingBy(BrandTicket::serial));
        return bySerial.entrySet().stream()
                .filter(e -> e.getValue().size() > 1)
                .map(e -> {
                    List<BrandTicket> list = e.getValue();
                    BrandTicket latest = list.stream()
                            .max(Comparator.comparing(BrandTicketQueryService::ticketDate,
                                    Comparator.nullsFirst(Comparator.naturalOrder())))
                            .orElse(list.get(0));
                    return new RobotCount(e.getKey(), latest.model(), latest.project(), list.size(),
                            BrandTicketQueryService.ticketDate(latest));
                })
                .sorted(Comparator.comparingInt(RobotCount::count).reversed().thenComparing(RobotCount::serial))
                .limit(TOP_N)
                .toList();
    }

    /**
     * Share of in-range tickets with a serial that were followed by another ticket on
     * the same serial within {@link #REPEAT_DAYS}. The follow-up is looked for in the
     * whole set, so a repeat just past the range's end still counts.
     */
    private static Double repeatRate(List<BrandTicket> inRange, List<BrandTicket> all) {
        Map<String, List<LocalDate>> datesBySerial = all.stream()
                .filter(t -> t.serial() != null && BrandTicketQueryService.ticketDate(t) != null)
                .collect(Collectors.groupingBy(BrandTicket::serial,
                        Collectors.mapping(BrandTicketQueryService::ticketDate, Collectors.toList())));

        List<BrandTicket> sample = inRange.stream()
                .filter(t -> t.serial() != null && BrandTicketQueryService.ticketDate(t) != null)
                .toList();
        if (sample.isEmpty()) return null;

        long repeats = sample.stream().filter(t -> {
            LocalDate d = BrandTicketQueryService.ticketDate(t);
            return datesBySerial.get(t.serial()).stream()
                    .anyMatch(other -> other.isAfter(d) && !other.isAfter(d.plusDays(REPEAT_DAYS)));
        }).count();
        return round1(100.0 * repeats / sample.size());
    }

    private static Double median(List<Integer> values) {
        if (values.isEmpty()) return null;
        List<Integer> sorted = values.stream().sorted().toList();
        int n = sorted.size();
        return n % 2 == 1 ? (double) sorted.get(n / 2)
                : round1((sorted.get(n / 2 - 1) + sorted.get(n / 2)) / 2.0);
    }

    private static Double share(List<Integer> values, java.util.function.IntPredicate test) {
        if (values.isEmpty()) return null;
        long hits = values.stream().filter(test::test).count();
        return round1(100.0 * hits / values.size());
    }

    private static double round1(double v) {
        return Math.round(v * 10.0) / 10.0;
    }
}
