package com.raaspal.robotrecommendation.cm.controller;

import com.raaspal.robotrecommendation.ai.dto.CmReportDraft;
import com.raaspal.robotrecommendation.auth.security.UserPrincipal;
import com.raaspal.robotrecommendation.cm.dto.CmReportParseRequest;
import com.raaspal.robotrecommendation.cm.dto.CmReportRequest;
import com.raaspal.robotrecommendation.cm.dto.CmReportResponse;
import com.raaspal.robotrecommendation.cm.dto.CmTicketDraft;
import com.raaspal.robotrecommendation.cm.dto.CmTicketSummary;
import com.raaspal.robotrecommendation.cm.service.CmReportService;
import com.raaspal.robotrecommendation.common.response.ApiResponse;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.UUID;

/**
 * Corrective Maintenance reports. Every endpoint is authenticated via
 * SecurityConfig's catch-all.
 */
@RestController
@RequestMapping("/api/v1/cm-reports")
@RequiredArgsConstructor
public class CmReportController {

    private final CmReportService cmReportService;

    /**
     * Extracts report fields from a pasted service ticket for operator review.
     * Deliberately does not persist — see {@link CmReportService}.
     */
    @PostMapping("/parse")
    public ApiResponse<CmReportDraft> parse(@Valid @RequestBody CmReportParseRequest request) {
        return ApiResponse.success(cmReportService.parse(request.sourceText()));
    }

    /**
     * The monday tickets a report can be started from - the All Case group of the
     * Cleaning and Delivery boards, as the daily sync last saw them.
     */
    @GetMapping("/tickets")
    public ApiResponse<List<CmTicketSummary>> tickets(
            @RequestParam(required = false) CmTicketSummary.CmTicketBoard board,
            @RequestParam(required = false) String q) {
        return ApiResponse.success(cmReportService.tickets(board, q));
    }

    /**
     * Draft a report from one ticket: columns and comment thread in, fields out, for
     * the staff to review. Persists nothing, like {@link #parse}.
     */
    @PostMapping("/tickets/{caseTicketId}/parse")
    public ApiResponse<CmTicketDraft> parseTicket(@PathVariable UUID caseTicketId) {
        return ApiResponse.success(cmReportService.parseTicket(caseTicketId));
    }

    /** History list, newest first. {@code q} matches ticket no., customer, or serial number. */
    @GetMapping
    public ApiResponse<List<CmReportResponse>> list(@RequestParam(required = false) String q) {
        return ApiResponse.success(cmReportService.search(q));
    }

    @GetMapping("/{id}")
    public ApiResponse<CmReportResponse> getById(@PathVariable UUID id) {
        return ApiResponse.success(cmReportService.getById(id));
    }

    @PostMapping
    public ApiResponse<CmReportResponse> create(
            @Valid @RequestBody CmReportRequest request,
            @AuthenticationPrincipal UserPrincipal principal) {
        UUID createdBy = principal == null ? null : principal.getId();
        return ApiResponse.success("CM report saved", cmReportService.create(request, createdBy));
    }

    @PutMapping("/{id}")
    public ApiResponse<CmReportResponse> update(
            @PathVariable UUID id,
            @Valid @RequestBody CmReportRequest request) {
        return ApiResponse.success("CM report updated", cmReportService.update(id, request));
    }

    @DeleteMapping("/{id}")
    public ApiResponse<Void> delete(@PathVariable UUID id) {
        cmReportService.delete(id);
        return ApiResponse.success("CM report deleted");
    }
}
