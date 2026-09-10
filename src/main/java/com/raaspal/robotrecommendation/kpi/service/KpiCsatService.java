package com.raaspal.robotrecommendation.kpi.service;

import com.raaspal.robotrecommendation.common.exception.BadRequestException;
import com.raaspal.robotrecommendation.kpi.config.KpiCsatProperties;
import com.raaspal.robotrecommendation.kpi.csat.CsatStream;
import com.raaspal.robotrecommendation.kpi.csat.CsatWorkbook;
import com.raaspal.robotrecommendation.kpi.csat.CsatWorkbook.MonthAggregate;
import com.raaspal.robotrecommendation.kpi.csat.CsatWorkbookParser;
import com.raaspal.robotrecommendation.kpi.csat.CsatWorkbookSource;
import com.raaspal.robotrecommendation.kpi.csat.CsatWorkbookSource.WorkbookFile;
import com.raaspal.robotrecommendation.kpi.dto.KpiCsatResponse;
import com.raaspal.robotrecommendation.kpi.dto.KpiCsatResponse.Bucket;
import com.raaspal.robotrecommendation.kpi.dto.KpiCsatResponse.MonthCsat;
import com.raaspal.robotrecommendation.kpi.dto.KpiCsatResponse.SourceFile;
import com.raaspal.robotrecommendation.kpi.dto.KpiCsatResponse.Totals;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.io.InputStream;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.YearMonth;
import java.time.ZoneId;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.EnumMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Collectors;

/**
 * CSAT from the survey workbooks: parsed on request, cached until a file
 * changes. The whole set is four small workbooks the RE team replaces once a
 * month, so there is nothing to persist and no sync to schedule — when the
 * files change, the next request sees it.
 *
 * <p>The figures are the RE team's own: each sheet's Top Box is read as it is,
 * and totals over months or surveys — which no sheet holds — are combined the
 * way the deck combines them, so every deck figure reproduces.
 */
@Slf4j
@Service
public class KpiCsatService {

    /** The widest range one request may ask for; same limit as the CM-case KPIs. */
    public static final int MAX_MONTHS = KpiCaseMetricsService.MAX_MONTHS;

    private final CsatWorkbookSource source;
    private final CsatWorkbookParser parser;
    private final KpiCsatProperties properties;

    private Snapshot cached;

    public KpiCsatService(CsatWorkbookSource source, CsatWorkbookParser parser, KpiCsatProperties properties) {
        this.source = source;
        this.parser = parser;
        this.properties = properties;
    }

    public KpiCsatResponse monthly(YearMonth from, YearMonth to) {
        if (to.isBefore(from)) {
            throw new BadRequestException("'to' must not be before 'from'");
        }
        if (ChronoUnit.MONTHS.between(from, to) + 1 > MAX_MONTHS) {
            throw new BadRequestException("The range must not exceed " + MAX_MONTHS + " months");
        }
        Snapshot snapshot = snapshot();

        List<MonthCsat> months = new ArrayList<>();
        Map<CsatStream, Tally> rangeTallies = new EnumMap<>(CsatStream.class);
        Tally rangeOverall = new Tally();
        for (YearMonth month = from; !month.isAfter(to); month = month.plusMonths(1)) {
            Map<CsatStream, Bucket> buckets = new EnumMap<>(CsatStream.class);
            Tally monthOverall = new Tally();
            for (CsatStream stream : CsatStream.values()) {
                Tally tally = new Tally();
                CsatWorkbook workbook = snapshot.byStream().get(stream);
                MonthAggregate aggregate = workbook == null ? null : workbook.months().get(month);
                if (aggregate != null) {
                    tally.add(aggregate);
                    monthOverall.add(aggregate);
                    rangeTallies.computeIfAbsent(stream, s -> new Tally()).add(aggregate);
                    rangeOverall.add(aggregate);
                }
                buckets.put(stream, tally.bucket());
            }
            months.add(new MonthCsat(month.toString(), monthOverall.bucket(),
                    buckets.get(CsatStream.INSTALLATION), buckets.get(CsatStream.PM),
                    buckets.get(CsatStream.CM_CLEANING), buckets.get(CsatStream.CM_DELIVERY)));
        }
        Totals totals = new Totals(rangeOverall.bucket(),
                rangeTallies.getOrDefault(CsatStream.INSTALLATION, new Tally()).bucket(),
                rangeTallies.getOrDefault(CsatStream.PM, new Tally()).bucket(),
                rangeTallies.getOrDefault(CsatStream.CM_CLEANING, new Tally()).bucket(),
                rangeTallies.getOrDefault(CsatStream.CM_DELIVERY, new Tally()).bucket());

        return new KpiCsatResponse(from.toString(), to.toString(), months, totals,
                sourceFiles(snapshot), snapshot.asOf().map(YearMonth::toString).orElse(null),
                true, snapshot.warnings(), definitions());
    }

    /** Drops the cache and reads the source again; what the console's reload button calls. */
    public SourceStatus reload() {
        synchronized (this) {
            cached = null;
        }
        return status();
    }

    public SourceStatus status() {
        Snapshot snapshot = snapshot();
        return new SourceStatus(source.describe(), sourceFiles(snapshot),
                snapshot.byStream().keySet().stream().map(CsatStream::key).toList(),
                snapshot.asOf().map(YearMonth::toString).orElse(null),
                snapshot.warnings(), toLocal(snapshot.loadedAt()));
    }

    /**
     * The parsed workbooks, re-read only when the set of files (names, sizes,
     * timestamps) differs from last time. Listing the folder is a few stats;
     * parsing is a few hundred milliseconds, so it is done at most once per
     * change rather than once per request.
     */
    private synchronized Snapshot snapshot() {
        List<WorkbookFile> files = source.list();
        String signature = files.stream().map(WorkbookFile::fingerprint).sorted().collect(Collectors.joining(";"));
        if (cached != null && cached.signature().equals(signature)) {
            return cached;
        }
        List<String> warnings = new ArrayList<>();
        Map<CsatStream, CsatWorkbook> byStream = new EnumMap<>(CsatStream.class);
        List<CsatWorkbook> parsed = new ArrayList<>();
        for (WorkbookFile file : files) {
            CsatWorkbook workbook;
            try (InputStream in = file.open()) {
                workbook = parser.parse(file.name(), file.lastModified(), in);
            } catch (Exception e) {
                log.warn("CSAT workbook {} could not be read: {}", file.name(), e.toString());
                warnings.add(file.name() + " could not be read: " + e.getMessage());
                continue;
            }
            parsed.add(workbook);
            warnings.addAll(workbook.warnings());
            if (workbook.stream() == null) {
                continue;
            }
            CsatWorkbook other = byStream.get(workbook.stream());
            if (other == null) {
                byStream.put(workbook.stream(), workbook);
            } else {
                // Two files for one survey: last month's copy not deleted, most likely.
                // The newer file is the current one; say so rather than double-count.
                CsatWorkbook newer = workbook.lastModified().isAfter(other.lastModified()) ? workbook : other;
                CsatWorkbook older = newer == workbook ? other : workbook;
                byStream.put(workbook.stream(), newer);
                warnings.add("Both '" + newer.fileName() + "' and '" + older.fileName() + "' are the "
                        + workbook.stream().key() + " survey; using the newer, '" + newer.fileName() + "'");
            }
        }
        for (CsatStream stream : CsatStream.values()) {
            if (!byStream.containsKey(stream)) {
                warnings.add("No workbook for the " + stream.key() + " survey in " + source.describe());
            }
        }
        cached = new Snapshot(signature, parsed, byStream, List.copyOf(warnings), Instant.now());
        log.info("CSAT workbooks loaded from {}: {} files, surveys {}, as of {}", source.describe(),
                parsed.size(), byStream.keySet(), cached.asOf().map(YearMonth::toString).orElse("none"));
        return cached;
    }

    private List<SourceFile> sourceFiles(Snapshot snapshot) {
        return snapshot.workbooks().stream()
                .sorted(Comparator.comparing(CsatWorkbook::fileName))
                .map(w -> new SourceFile(
                        w.fileName(),
                        w.stream() == null ? null : w.stream().key(),
                        toLocal(w.lastModified()),
                        w.months().keySet().stream().min(Comparator.naturalOrder()).map(YearMonth::toString).orElse(null),
                        w.months().keySet().stream().max(Comparator.naturalOrder()).map(YearMonth::toString).orElse(null)))
                .toList();
    }

    private LocalDateTime toLocal(Instant instant) {
        return LocalDateTime.ofInstant(instant, ZoneId.of(properties.getZone()));
    }

    private static Map<String, String> definitions() {
        Map<String, String> d = new LinkedHashMap<>();
        d.put("topBox", "For one survey in one month: the month sheet's own Top Box cell, =AVERAGE(Q10:Q14) — the "
                + "average over the five questions of the share of ratings that were 5. Read as the RE team computed it, "
                + "never recomputed.");
        d.put("totals", "No sheet holds a total over months or over surveys, so those are combined the way the deck "
                + "combines them: all ratings of 5 over all ratings given. Every deck figure reproduces.");
        d.put("responseRate", "Responses over customers the team tried to reach that month (the sheet's '# customers'), "
                + "not over jobs done.");
        d.put("surveys", "Four surveys, one workbook each: installation, PM (the workbook says MA), CM cleaning, CM delivery. "
                + "A month a survey has no sheet for is reported as not surveyed, not as zero.");
        d.put("source", "The RE team's own monthly workbooks, read as they are; only the month sheets, found by label. "
                + "Nothing per respondent is read. The figures change when the workbooks are replaced, so this KPI is not live.");
        return d;
    }

    /** What the source holds right now; the reload endpoint returns it. */
    public record SourceStatus(
            String source,
            List<SourceFile> files,
            List<String> surveys,
            String asOf,
            List<String> warnings,
            LocalDateTime loadedAt
    ) {
    }

    private record Snapshot(
            String signature,
            List<CsatWorkbook> workbooks,
            Map<CsatStream, CsatWorkbook> byStream,
            List<String> warnings,
            Instant loadedAt
    ) {
        /** The latest month any survey has responses for. */
        Optional<YearMonth> asOf() {
            return byStream.values().stream()
                    .flatMap(w -> w.months().values().stream())
                    .filter(m -> m.responses() > 0)
                    .map(MonthAggregate::month)
                    .max(Comparator.naturalOrder());
        }
    }

    /**
     * Running sums for one bucket. A bucket that covers exactly one sheet shows
     * that sheet's own Top Box cell. One that covers several — a survey over a
     * range, or every survey in a month — has no cell to show, and combines
     * them the way the deck does: all ratings of 5 over all ratings given.
     */
    private static final class Tally {
        private boolean surveyed;
        private int sheets;
        private double onlyTopBox;
        private int customers;
        private int responses;
        private int notEvaluated;
        private int fives;
        private int ratings;

        void add(MonthAggregate a) {
            surveyed = true;
            sheets++;
            onlyTopBox = a.topBox();
            customers += a.customers();
            responses += a.responses();
            notEvaluated += a.notEvaluated();
            fives += a.fives();
            ratings += a.ratings();
        }

        Bucket bucket() {
            Double topBox;
            if (sheets == 1) {
                topBox = Math.round(onlyTopBox * 1000.0) / 10.0;
            } else {
                topBox = rate(fives, ratings);
            }
            return new Bucket(surveyed, customers, responses, notEvaluated, topBox, rate(responses, customers));
        }

        private static Double rate(int numerator, int denominator) {
            return denominator == 0 ? null : Math.round(numerator * 1000.0 / denominator) / 10.0;
        }
    }
}
