package com.raaspal.robotrecommendation.casereport.brand;

import com.raaspal.robotrecommendation.casereport.brand.dto.BrandTicket;
import com.raaspal.robotrecommendation.casereport.brand.dto.BrandTicketSummary;
import com.raaspal.robotrecommendation.common.response.ApiResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.NoSuchElementException;
import java.util.Objects;

/**
 * Per-brand service-ticket analytics: {@code /api/v1/tickets/{brand}}.
 *
 * <p>The brand is a slug from {@code app.tickets.brands}; anything else is a 404. RE
 * team and admins only - the rows carry customer names and phone numbers.
 */
@RestController
@RequestMapping("/api/v1/tickets/{brand}")
@RequiredArgsConstructor
@PreAuthorize("hasAnyRole('ADMIN','RAASPAL_TEAM')")
public class BrandTicketController {

    private final BrandTicketQueryService query;
    private final BrandTicketAnalyticsService analytics;
    private final BrandTicketExcelWriter excel;
    private final BrandTicketSyncService sync;

    /** What the page's "last synced" line and the Refresh button read. */
    public record SyncStatus(String brand, LocalDateTime lastSyncedAt, int ticketCount,
                             BrandTicketSyncService.LastRun lastRun) {
    }

    @GetMapping("/summary")
    public ApiResponse<BrandTicketSummary> summary(
            @PathVariable String brand,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to) {
        BrandTicketProperties.Brand b = brand(brand);
        return ApiResponse.success(analytics.summarise(b, query.all(b), from, to,
                LocalDate.now(BrandTicketQueryService.BUSINESS_ZONE)));
    }

    /**
     * The tickets themselves, with threads. {@code scope=open} keeps only the ones still
     * being worked; the date range applies either way.
     */
    @GetMapping
    public ApiResponse<List<BrandTicket>> tickets(
            @PathVariable String brand,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to,
            @RequestParam(defaultValue = "all") String scope) {
        BrandTicketProperties.Brand b = brand(brand);
        boolean openOnly = "open".equalsIgnoreCase(scope);
        return ApiResponse.success(select(query.all(b), from, to, openOnly));
    }

    /** The three-sheet workbook for the range. Same rows the page shows. */
    @GetMapping("/export")
    public ResponseEntity<byte[]> export(
            @PathVariable String brand,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to) {
        BrandTicketProperties.Brand b = brand(brand);
        List<BrandTicket> all = query.all(b);
        BrandTicketSummary summary = analytics.summarise(b, all, from, to,
                LocalDate.now(BrandTicketQueryService.BUSINESS_ZONE));
        byte[] bytes = excel.write(summary, select(all, from, to, false));
        return ResponseEntity.ok()
                .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=\"" + excel.filename(summary) + "\"")
                .contentType(MediaType.parseMediaType(
                        "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet"))
                .body(bytes);
    }

    /** Pull the brand's rows from monday now. One API call; a few seconds. */
    @PostMapping("/sync")
    public ApiResponse<SyncStatus> sync(@PathVariable String brand) {
        BrandTicketProperties.Brand b = brand(brand);
        BrandTicketSyncService.LastRun run = sync.sync(b);
        if (!run.ok()) {
            throw new ResponseStatusException(HttpStatus.BAD_GATEWAY, "monday sync failed: " + run.error());
        }
        return ApiResponse.success(status(b));
    }

    @GetMapping("/sync/status")
    public ApiResponse<SyncStatus> syncStatus(@PathVariable String brand) {
        return ApiResponse.success(status(brand(brand)));
    }

    private SyncStatus status(BrandTicketProperties.Brand b) {
        List<BrandTicket> all = query.all(b);
        LocalDateTime last = all.stream().map(BrandTicket::lastSyncedAt)
                .filter(Objects::nonNull).max(LocalDateTime::compareTo).orElse(null);
        return new SyncStatus(b.getKey(), last, all.size(), sync.lastRun(b.getKey()));
    }

    private static List<BrandTicket> select(List<BrandTicket> all, LocalDate from, LocalDate to, boolean openOnly) {
        return all.stream()
                .filter(t -> !openOnly || t.open())
                .filter(t -> {
                    LocalDate d = BrandTicketQueryService.ticketDate(t);
                    if (d == null) return from == null && to == null;
                    return (from == null || !d.isBefore(from)) && (to == null || !d.isAfter(to));
                })
                .toList();
    }

    private BrandTicketProperties.Brand brand(String key) {
        try {
            return query.brand(key);
        } catch (NoSuchElementException e) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, e.getMessage());
        }
    }
}
