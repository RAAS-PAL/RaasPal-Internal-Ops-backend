package com.raaspal.robotrecommendation.casereport.view;

import com.raaspal.robotrecommendation.casereport.dto.CaseReportRow;
import com.raaspal.robotrecommendation.casereport.service.CaseReportExcelWriter;
import lombok.RequiredArgsConstructor;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * A pending tab's cases for the period picked, as the Excel the sheets are sent in: the
 * Excel beside the tab's calendar. The whole day's sheet stays on its details page.
 */
@RestController
@RequestMapping("/api/v1/case-reports/views")
@RequiredArgsConstructor
public class CaseViewController {

    private static final DateTimeFormatter DAY = DateTimeFormatter.ofPattern("d MMM yyyy", Locale.ENGLISH);

    /** Each tab's name, as its own tab reads. */
    static final Map<CaseViews.View, String> NAMES = Map.of(
            CaseViews.View.INTERNAL, "Internal",
            CaseViews.View.PCS, "PCS",
            CaseViews.View.MAKRO, "Makro",
            CaseViews.View.IFS, "IFS",
            CaseViews.View.MK, "MK",
            CaseViews.View.ON_HOLD, "On Hold");

    private final CaseViews views;
    private final CaseReportExcelWriter excel;

    /**
     * @param scope BOTH (default), CLEANING or DELIVERY
     * @param from  the period's first day; with {@code to}, or neither for every open case
     */
    @GetMapping("/{view}/export")
    public ResponseEntity<byte[]> export(@PathVariable String view,
                                         @RequestParam(required = false) String scope,
                                         @RequestParam(required = false)
                                         @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
                                         @RequestParam(required = false)
                                         @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to) {
        CaseViews.View v = CaseViews.View.of(view);
        String board = CaseViews.scope(scope);
        CaseViews.requirePeriod(from, to);

        CaseViews.Listing listing = views.list(v, board, from, to, false);
        List<CaseReportRow> rows = CaseViews.rows(listing.entries());
        LocalDate asOf = listing.asOf() != null ? listing.asOf() : LocalDate.now(CaseViews.BUSINESS_ZONE);

        String name = NAMES.get(v) + ("BOTH".equals(board) ? "" : " (" + capital(board) + ")");
        String heading = name + " — " + (from == null
                ? "every open case, " + DAY.format(asOf)
                : "cases opened " + DAY.format(from) + " – " + DAY.format(to) + ", as on " + DAY.format(asOf));
        CaseReportExcelWriter.Layout layout = new CaseReportExcelWriter.Layout(
                "BOTH".equals(board), v != CaseViews.View.MAKRO, true);
        String file = v.slug + "-cases-" + (from == null ? "all-" + asOf : from + "-to-" + to)
                + ("BOTH".equals(board) ? "" : "-" + board.toLowerCase(Locale.ROOT)) + ".xlsx";

        return ResponseEntity.ok()
                .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=\"" + file + "\"")
                .contentType(MediaType.parseMediaType(
                        "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet"))
                .body(excel.write(heading, name, layout, rows));
    }

    private static String capital(String board) {
        return board.charAt(0) + board.substring(1).toLowerCase(Locale.ROOT);
    }
}
