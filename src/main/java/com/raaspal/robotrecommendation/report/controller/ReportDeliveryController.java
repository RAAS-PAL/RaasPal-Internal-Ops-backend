package com.raaspal.robotrecommendation.report.controller;

import com.raaspal.robotrecommendation.common.response.ApiResponse;
import com.raaspal.robotrecommendation.customer.entity.CustomerProfile;
import com.raaspal.robotrecommendation.customer.repository.CustomerProfileRepository;
import com.raaspal.robotrecommendation.report.dto.ReportSendResponse;
import com.raaspal.robotrecommendation.report.entity.ReportSend;
import com.raaspal.robotrecommendation.report.service.ReportDeliveryService;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * Admin endpoints for the automated report delivery: run a month now (manual
 * trigger / resend), send a single customer, and view delivery history for the
 * "Manage report automation" tab. All authenticated (not under /public).
 */
@RestController
@RequestMapping("/api/v1/reports/delivery")
@RequiredArgsConstructor
public class ReportDeliveryController {

    private final ReportDeliveryService reportDeliveryService;
    private final CustomerProfileRepository customerProfileRepository;

    /**
     * Start the whole-month delivery in the background and return immediately
     * (the run syncs each customer's robots as it sends them, which takes
     * minutes). Idempotent per customer, and at most one run executes at a
     * time — starting while a run is in progress is rejected with a clear
     * message. {@code excludedCustomerIds} holds back specific customers for
     * this run only (e.g. a site not fully registered yet) — they're left
     * eligible for a future run. Poll {@code /status} or the history for progress.
     */
    @PostMapping("/run")
    public ApiResponse<ReportDeliveryService.RunStatus> runMonth(
            @RequestParam String month,
            @RequestParam(required = false) List<UUID> excludedCustomerIds) {
        Set<UUID> excluded = excludedCustomerIds == null ? Set.of() : new HashSet<>(excludedCustomerIds);
        boolean started = reportDeliveryService.startRunAsync(month, excluded);
        String message = started
                ? "Delivery run started for " + month
                        + (excluded.isEmpty() ? "" : " (excluding " + excluded.size() + " customer(s))")
                : "A delivery run is already in progress";
        return ApiResponse.success(message, reportDeliveryService.status());
    }

    /** Whether a delivery run is executing, and the last finished run's summary. */
    @GetMapping("/status")
    public ApiResponse<ReportDeliveryService.RunStatus> status() {
        return ApiResponse.success(reportDeliveryService.status());
    }

    /**
     * Send (or resend) one customer's bundle for the month, in the background —
     * syncing one customer's robots can take minutes for large sites, so the
     * request returns immediately and the outcome appears in the history.
     * Rejected (started=false in the message) while another delivery is running.
     */
    @PostMapping("/send")
    public ApiResponse<ReportDeliveryService.RunStatus> sendCustomer(
            @RequestParam UUID customerProfileId,
            @RequestParam String month) {
        boolean started = reportDeliveryService.startSendAsync(customerProfileId, month);
        String message = started
                ? "Send started for " + customerName(customerProfileId) + " (" + month
                        + ") — the result will appear in the history below."
                : "A delivery is already in progress — try again when it finishes.";
        return ApiResponse.success(message, reportDeliveryService.status());
    }

    /** Delivery history for a month, newest first. */
    @GetMapping("/history")
    public ApiResponse<List<ReportSendResponse>> history(@RequestParam String month) {
        List<ReportSend> sends = reportDeliveryService.historyForMonth(month);
        Map<UUID, String> names = new HashMap<>();
        List<ReportSendResponse> rows = sends.stream()
                .map(s -> ReportSendResponse.of(s,
                        names.computeIfAbsent(s.getCustomerProfileId(), this::customerName)))
                .toList();
        return ApiResponse.success(rows);
    }

    private String customerName(UUID customerProfileId) {
        return customerProfileRepository.findById(customerProfileId)
                .map(CustomerProfile::getCompanyName)
                .orElse("(unknown)");
    }
}
