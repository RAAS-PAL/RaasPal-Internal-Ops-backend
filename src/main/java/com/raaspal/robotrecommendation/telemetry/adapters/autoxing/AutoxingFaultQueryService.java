package com.raaspal.robotrecommendation.telemetry.adapters.autoxing;

import com.raaspal.robotrecommendation.telemetry.adapters.autoxing.dto.AutoxingFaultSummary;
import com.raaspal.robotrecommendation.telemetry.adapters.autoxing.dto.AutoxingFaultSummary.FaultEvent;
import com.raaspal.robotrecommendation.telemetry.adapters.autoxing.dto.AutoxingFaultSummary.FaultItem;
import com.raaspal.robotrecommendation.telemetry.entity.RobotFaultEvent;
import com.raaspal.robotrecommendation.telemetry.entity.RobotFaultEvent.Kind;
import com.raaspal.robotrecommendation.telemetry.repository.RobotFaultEventRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.util.*;
import java.util.stream.Collectors;

/** Reads the recorded fault history back out for a robot and a period. */
@Service
@RequiredArgsConstructor
public class AutoxingFaultQueryService {

    private final RobotFaultEventRepository faults;

    @Transactional(readOnly = true)
    public AutoxingFaultSummary summarise(String robotId, LocalDate from, LocalDate to) {
        Instant start = from.atStartOfDay(AutoxingPerformanceService.BANGKOK).toInstant();
        Instant end = to.plusDays(1).atStartOfDay(AutoxingPerformanceService.BANGKOK).toInstant();
        Instant since = faults.findRecordingSince(AutoxingFaultPollService.BRAND);
        List<RobotFaultEvent> rows = faults.findOverlapping(AutoxingFaultPollService.BRAND, robotId, start, end);
        return summarise(rows, since, start, end, Instant.now());
    }

    /** Pure: the counts for a list of rows, durations clipped to {@code [start, end)} and to now. */
    static AutoxingFaultSummary summarise(List<RobotFaultEvent> rows, Instant since, Instant start, Instant end,
                                          Instant now) {
        Instant stop = end.isBefore(now) ? end : now;

        Map<Integer, List<RobotFaultEvent>> byCode = rows.stream()
                .filter(r -> r.getKind() == Kind.ERROR)
                .collect(Collectors.groupingBy(RobotFaultEvent::getErrorCode));
        List<FaultItem> errors = byCode.entrySet().stream()
                .map(e -> {
                    List<RobotFaultEvent> list = e.getValue();
                    RobotFaultEvent withText = list.stream().filter(r -> r.getMessage() != null).findFirst()
                            .orElse(list.get(0));
                    return new FaultItem(e.getKey(), withText.getMessage(), withText.getErrorLevel(),
                            list.size(), seconds(list, start, stop),
                            list.stream().anyMatch(r -> r.getClearedAt() == null));
                })
                .sorted(Comparator.comparingInt(FaultItem::occurrences).reversed()
                        .thenComparing(Comparator.comparingLong(FaultItem::activeSeconds).reversed()))
                .toList();

        List<RobotFaultEvent> estops = rows.stream().filter(r -> r.getKind() == Kind.EMERGENCY_STOP).toList();
        List<RobotFaultEvent> offline = rows.stream().filter(r -> r.getKind() == Kind.OFFLINE).toList();

        return AutoxingFaultSummary.builder()
                .recordingSince(since)
                .partialPeriod(since == null || since.isAfter(start))
                .errorOccurrences(errors.stream().mapToInt(FaultItem::occurrences).sum())
                .errors(errors)
                .emergencyStops(estops.size())
                .emergencyStopSeconds(seconds(estops, start, stop))
                .offlineSeconds(seconds(offline, start, stop))
                .events(rows.stream()
                        .map(r -> new FaultEvent(r.getKind().name(), r.getErrorCode(), r.getMessage(),
                                r.getFirstSeenAt(), r.getClearedAt()))
                        .toList())
                .build();
    }

    /** Total time the rows were active inside the window; an open row runs to the window's stop. */
    private static long seconds(List<RobotFaultEvent> rows, Instant start, Instant stop) {
        long total = 0;
        for (RobotFaultEvent r : rows) {
            Instant a = r.getFirstSeenAt().isBefore(start) ? start : r.getFirstSeenAt();
            Instant b = r.getClearedAt() == null || r.getClearedAt().isAfter(stop) ? stop : r.getClearedAt();
            if (b.isAfter(a)) total += Duration.between(a, b).getSeconds();
        }
        return total;
    }
}
