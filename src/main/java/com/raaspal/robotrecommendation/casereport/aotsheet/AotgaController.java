package com.raaspal.robotrecommendation.casereport.aotsheet;

import com.raaspal.robotrecommendation.auth.security.UserPrincipal;
import com.raaspal.robotrecommendation.common.response.ApiResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.LocalDate;
import java.util.Locale;

/**
 * The AOTGA tracker on the AOT tab: each case's stage from AOT's sheet, and the claim of
 * the old part, which RAASPAL records here.
 */
@RestController
@RequestMapping("/api/v1/case-reports/aotga")
@RequiredArgsConstructor
public class AotgaController {

    private final AotgaTracker tracker;
    private final AotgaExcelWriter excel;

    /**
     * Every case not yet claimed, and those claimed lately: today's read now (syncing the
     * sheet first if it is a minute old), a past day's as it was kept that day.
     */
    @GetMapping("/cases")
    public ApiResponse<AotgaTracker.View> cases(
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate date) {
        return ApiResponse.success(tracker.view(date));
    }

    /** Records that a case's old part was sent back to the manufacturer. Staff only. */
    @PutMapping("/claims")
    @PreAuthorize("hasAnyRole('ADMIN','RAASPAL_TEAM')")
    public ApiResponse<AotgaTracker.View> claim(@RequestBody AotgaTracker.ClaimRequest request,
                                                @AuthenticationPrincipal UserPrincipal me) {
        return ApiResponse.success("Claim recorded", tracker.claim(request, me == null ? null : me.getUsername()));
    }

    /** Takes that back: not sent after all. Staff only. */
    @DeleteMapping("/claims")
    @PreAuthorize("hasAnyRole('ADMIN','RAASPAL_TEAM')")
    public ApiResponse<AotgaTracker.View> undo(@RequestParam String ticket,
                                               @AuthenticationPrincipal UserPrincipal me) {
        return ApiResponse.success("Claim undone", tracker.undo(ticket, me == null ? null : me.getUsername()));
    }

    /**
     * The tab's cases as a workbook, as the tab shows them: the cases issued in the period
     * (every case for all time), their stage as of the day the period is read on (today
     * when none is given), in the table's order.
     *
     * @param cadence {@code DAILY}, {@code WEEKLY}, {@code MONTHLY} or {@code ALL}; daily when absent
     * @param from    the period's first day; the read day when absent
     * @param to      the period's last day; the read day when absent
     * @param sort    the column the table is sorted by; newest issue date first when absent
     * @param dir     {@code asc} or {@code desc}
     */
    @GetMapping("/export")
    public ResponseEntity<byte[]> export(
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate date,
            @RequestParam(required = false) String cadence,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to,
            @RequestParam(required = false) String sort,
            @RequestParam(required = false) String dir) {
        AotgaTracker.View view = tracker.view(date);
        AotgaExcelWriter.Period period = period(cadence, from, to, date == null ? view.asOf() : date);
        byte[] bytes = excel.write(view, period, AotgaSort.of(sort, dir));
        return ResponseEntity.ok()
                .header(HttpHeaders.CONTENT_DISPOSITION,
                        "attachment; filename=\"" + period.fileName(view.asOf()) + ".xlsx\"")
                .contentType(MediaType.parseMediaType(
                        "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet"))
                .body(bytes);
    }

    /** The period as the tab sent it; a missing or backwards one is the day asked for alone. */
    static AotgaExcelWriter.Period period(String cadence, LocalDate from, LocalDate to, LocalDate asOf) {
        AotgaExcelWriter.Cadence kind;
        try {
            kind = cadence == null ? AotgaExcelWriter.Cadence.DAILY
                    : AotgaExcelWriter.Cadence.valueOf(cadence.trim().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException e) {
            kind = AotgaExcelWriter.Cadence.DAILY;
        }
        if (kind == AotgaExcelWriter.Cadence.ALL) return AotgaExcelWriter.Period.ALL_TIME;
        if (kind == AotgaExcelWriter.Cadence.DAILY) return AotgaExcelWriter.Period.day(from == null ? asOf : from);
        if (from == null || to == null || to.isBefore(from)) return AotgaExcelWriter.Period.day(asOf);
        return new AotgaExcelWriter.Period(kind, from, to);
    }
}
