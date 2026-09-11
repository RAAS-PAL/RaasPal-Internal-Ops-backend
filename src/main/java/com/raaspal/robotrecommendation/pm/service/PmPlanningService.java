package com.raaspal.robotrecommendation.pm.service;

import com.raaspal.robotrecommendation.pm.dto.*;
import com.raaspal.robotrecommendation.pm.entity.PmServiceLine;
import com.raaspal.robotrecommendation.pm.entity.PmStatusBucket;
import com.raaspal.robotrecommendation.pm.repository.PmVisitRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.time.temporal.WeekFields;
import java.util.*;

/**
 * Builds the two planner views out of the mirrored PM data.
 *
 * <p>Both views run the same date-range query and differ only in how the result is
 * folded. Grouping the year by ISO week happens here rather than in SQL, for two
 * reasons: {@code EXTRACT(ISOYEAR ...)} is Postgres-only and cannot run against the
 * H2 database the tests use, and a single query path means the filters cannot drift
 * between the grid and the month list. The volume makes it affordable - a year is a
 * few thousand visits, not a few million.
 */
@Service
@RequiredArgsConstructor
public class PmPlanningService {

    private final PmVisitRepository visitRepository;
    private final ProvinceResolver provinceResolver;

    /** The 52-week (sometimes 53-week) grid for one ISO year. */
    public PmYearResponse year(int year, PmFilter filter) {
        int weekCount = weeksInIsoYear(year);
        LocalDate from = isoWeekMonday(year, 1);
        LocalDate to = isoWeekMonday(year, weekCount).plusDays(6);

        List<PmVisitRepository.VisitRow> visits = query(from, to, false, filter);
        LocalDate today = LocalDate.now();

        Map<UUID, RowAccumulator> rowsById = new LinkedHashMap<>();
        Map<Integer, Map<String, Long>> weekTotals = new TreeMap<>();

        for (PmVisitRepository.VisitRow visit : visits) {
            int week = isoWeekOf(visit.getPlanDate());
            String bucket = effectiveBucket(visit, today);
            rowsById.computeIfAbsent(visit.getContractId(), id -> new RowAccumulator(visit)).add(week, bucket);
            weekTotals.computeIfAbsent(week, w -> new LinkedHashMap<>()).merge(bucket, 1L, Long::sum);
        }

        List<PmYearResponse.Row> rows = rowsById.values().stream()
                .map(RowAccumulator::build)
                // Geography first: the grid is read region by region when planning a trip.
                .sorted(Comparator.comparing((PmYearResponse.Row r) -> nullsLast(r.region()))
                        .thenComparing(r -> nullsLast(r.zone()))
                        .thenComparing(r -> nullsLast(r.province()))
                        .thenComparing(r -> nullsLast(r.name())))
                .toList();

        List<PmYearResponse.WeekTotal> totals = weekTotals.entrySet().stream()
                .map(entry -> new PmYearResponse.WeekTotal(entry.getKey(),
                        entry.getValue().values().stream().mapToLong(Long::longValue).sum(),
                        entry.getValue()))
                .toList();

        return new PmYearResponse(year, weekCount, rows, totals, summarise(visits, filter, today));
    }

    /** Every visit between two dates, plus tiles. */
    public PmMonthResponse range(LocalDate from, LocalDate to, boolean includeUndated, PmFilter filter) {
        List<PmVisitRepository.VisitRow> found = query(from, to, includeUndated, filter);
        LocalDate today = LocalDate.now();

        List<PmMonthResponse.Row> rows = found.stream()
                .map(row -> new PmMonthResponse.Row(
                        row.getVisitId(), row.getVisitName(), row.getPmSequence(), row.getPlanDate(),
                        row.getActionDate(), row.getTimeText(), row.getStatusRaw(), row.getStatusBucket(),
                        daysOverdue(row.getPlanDate(), row.getStatusBucket(), today), row.getOwnerNames(),
                        row.getContractId(), row.getItemName(), row.getCustomerName(), row.getProject(),
                        row.getServiceLine(), row.getProvince(), row.getRegion(), row.getZone(),
                        row.getRobotModel(), row.getRobotCount(), row.getContractType()))
                .toList();

        return new PmMonthResponse(from, to, summarise(found, filter, today), rows);
    }

    /** What the filter bar offers. */
    public PmFilterOptions filterOptions() {
        return new PmFilterOptions(
                Arrays.stream(PmServiceLine.values()).map(Enum::name).toList(),
                withUnassigned(provinceResolver.regions()),
                provinceResolver.zones(),
                withUnassigned(provinceResolver.provinces()),
                Arrays.stream(PmStatusBucket.values()).map(Enum::name).toList(),
                distinctOwners(),
                visitRepository.findCompanyOptions().stream()
                        .map(option -> new PmFilterOptions.Company(option.getCompany(), option.getSiteCount()))
                        .toList());
    }

    private List<PmVisitRepository.VisitRow> query(LocalDate from, LocalDate to, boolean includeUndated,
                                                   PmFilter filter) {
        List<PmVisitRepository.VisitRow> rows = visitRepository.findVisitsInRange(from, to, includeUndated,
                filter.serviceLine(), filter.region(), filter.zone(), filter.province(), filter.status(),
                filter.owner(), filter.q());

        // Excluded chains are dropped here rather than in the query. A NOT IN over a
        // variable-length list needs either an array function (Postgres-only, so the
        // H2 tests could not run it) or a rebuilt statement per request, and this
        // filter usually removes a handful of chains from a few thousand rows.
        if (filter.excludedCompanies() == null || filter.excludedCompanies().isEmpty()) {
            return rows;
        }
        return rows.stream().filter(row -> !filter.excludes(row.getCompany())).toList();
    }

    /**
     * Splits the owner column into individual engineers.
     *
     * <p>A visit can carry two names in one cell, so the distinct cell values are not
     * the distinct engineers.
     */
    private List<String> distinctOwners() {
        return visitRepository.findDistinctOwnerNames().stream()
                .filter(Objects::nonNull)
                .flatMap(names -> Arrays.stream(names.split(",")))
                .map(String::trim)
                .filter(name -> !name.isEmpty())
                .distinct()
                .sorted()
                .toList();
    }

    /**
     * UNASSIGNED is offered explicitly so contracts with no province stay reachable.
     * Around half the Delivery board lands there, and a list of only real provinces
     * would make them unselectable.
     */
    private static List<String> withUnassigned(List<String> values) {
        List<String> out = new ArrayList<>(values);
        out.add(ProvinceResolver.UNASSIGNED);
        return out;
    }

    /**
     * The bucket a grid cell should be coloured by.
     *
     * <p>Overdue is not a stored status - it is a missed plan date on work that is
     * not finished - so it is applied here, on the way into the cell.
     */
    private static String effectiveBucket(PmVisitRepository.VisitRow visit, LocalDate today) {
        return daysOverdue(visit.getPlanDate(), visit.getStatusBucket(), today) != null
                ? "OVERDUE"
                : visit.getStatusBucket();
    }

    /** Positive days past a missed plan date, or null when nothing is owed. */
    private static Integer daysOverdue(LocalDate planDate, String statusBucket, LocalDate today) {
        if (planDate == null || PmStatusBucket.COMPLETED.name().equals(statusBucket) || !planDate.isBefore(today)) {
            return null;
        }
        return (int) ChronoUnit.DAYS.between(planDate, today);
    }

    private PmSummary summarise(List<PmVisitRepository.VisitRow> visits, PmFilter filter, LocalDate today) {
        Map<String, Long> byStatus = new HashMap<>();
        Set<UUID> contracts = new HashSet<>();
        long robots = 0;
        long overdue = 0;

        for (PmVisitRepository.VisitRow visit : visits) {
            byStatus.merge(visit.getStatusBucket(), 1L, Long::sum);
            // A contract's robot count belongs to the site, not to each visit, so it
            // is added once however many times the contract appears.
            if (contracts.add(visit.getContractId()) && visit.getRobotCount() != null) {
                robots += visit.getRobotCount();
            }
            if (daysOverdue(visit.getPlanDate(), visit.getStatusBucket(), today) != null) {
                overdue++;
            }
        }

        return new PmSummary(visits.size(), contracts.size(), robots,
                byStatus.getOrDefault(PmStatusBucket.PLANNED.name(), 0L),
                byStatus.getOrDefault(PmStatusBucket.IN_PROGRESS.name(), 0L),
                byStatus.getOrDefault(PmStatusBucket.COMPLETED.name(), 0L),
                byStatus.getOrDefault(PmStatusBucket.UNPLANNED.name(), 0L),
                overdue,
                visitRepository.countUndated(filter.serviceLine()));
    }

    /**
     * 52 or 53, depending on the year.
     *
     * <p>Sent to the client rather than assumed: a grid hard-coded to 52 columns
     * silently drops week 53, which exists in 2026 among others.
     */
    public static int weeksInIsoYear(int year) {
        // 28 December is always in the final ISO week of its week-based year.
        return LocalDate.of(year, 12, 28).get(WeekFields.ISO.weekOfWeekBasedYear());
    }

    /** Monday of a given ISO week, matching the frontend's week helper. */
    static LocalDate isoWeekMonday(int isoYear, int week) {
        // ISO week 1 is the week containing 4 January.
        LocalDate jan4 = LocalDate.of(isoYear, 1, 4);
        LocalDate week1Monday = jan4.minusDays(jan4.getDayOfWeek().getValue() - 1L);
        return week1Monday.plusWeeks(week - 1L);
    }

    /** The ISO week number a date falls in. */
    static int isoWeekOf(LocalDate date) {
        return date.get(WeekFields.ISO.weekOfWeekBasedYear());
    }

    private static String nullsLast(String value) {
        return value == null ? "￿" : value;
    }

    /** Folds one contract's visits into a single grid row. */
    private static final class RowAccumulator {

        private final PmVisitRepository.VisitRow first;
        private final Map<Integer, Map<String, Long>> byWeek = new TreeMap<>();
        private long total;

        private RowAccumulator(PmVisitRepository.VisitRow first) {
            this.first = first;
        }

        private void add(int week, String bucket) {
            byWeek.computeIfAbsent(week, w -> new LinkedHashMap<>()).merge(bucket, 1L, Long::sum);
            total++;
        }

        private PmYearResponse.Row build() {
            Map<Integer, PmYearResponse.Cell> cells = new LinkedHashMap<>();
            byWeek.forEach((week, byStatus) -> {
                long weekTotal = byStatus.values().stream().mapToLong(Long::longValue).sum();
                cells.put(week, new PmYearResponse.Cell(week, weekTotal, byStatus, dominant(byStatus)));
            });
            return new PmYearResponse.Row(first.getContractId(), first.getItemName(), first.getCustomerName(),
                    first.getProject(), first.getServiceLine(), first.getProvince(), first.getRegion(),
                    first.getZone(), first.getRobotModel(), first.getRobotCount(), total, cells);
        }

        /**
         * Which status colours a cell holding more than one.
         *
         * <p>Ordered by what a planner needs to see, not by count: one overdue visit
         * among four completed ones is the thing worth noticing, so the most urgent
         * status wins rather than the most frequent.
         */
        private static String dominant(Map<String, Long> byStatus) {
            for (String bucket : List.of("OVERDUE", PmStatusBucket.UNPLANNED.name(),
                    PmStatusBucket.IN_PROGRESS.name(), PmStatusBucket.PLANNED.name(),
                    PmStatusBucket.COMPLETED.name())) {
                if (byStatus.containsKey(bucket)) {
                    return bucket;
                }
            }
            return PmStatusBucket.PLANNED.name();
        }
    }
}
