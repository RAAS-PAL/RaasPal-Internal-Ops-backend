package com.raaspal.robotrecommendation.cm.service;

import com.raaspal.robotrecommendation.ai.dto.CmReportDraft;
import com.raaspal.robotrecommendation.ai.service.CmReportExtractionService;
import com.raaspal.robotrecommendation.casereport.entity.CaseTicket;
import com.raaspal.robotrecommendation.casereport.entity.CaseTicketUpdate;
import com.raaspal.robotrecommendation.casereport.repository.CaseTicketRepository;
import com.raaspal.robotrecommendation.casereport.repository.CaseTicketUpdateRepository;
import com.raaspal.robotrecommendation.casereport.service.CaseTicketSyncService;
import com.raaspal.robotrecommendation.cm.dto.CmReportRequest;
import com.raaspal.robotrecommendation.cm.dto.CmReportResponse;
import com.raaspal.robotrecommendation.cm.dto.CmTicketDraft;
import com.raaspal.robotrecommendation.cm.dto.CmTicketSummary;
import com.raaspal.robotrecommendation.cm.entity.CmReport;
import com.raaspal.robotrecommendation.cm.repository.CmReportRepository;
import com.raaspal.robotrecommendation.common.exception.BadRequestException;
import com.raaspal.robotrecommendation.common.exception.ResourceNotFoundException;
import com.raaspal.robotrecommendation.user.entity.User;
import com.raaspal.robotrecommendation.user.repository.UserRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

/**
 * Corrective Maintenance reports: AI-assisted parsing of a pasted service ticket,
 * plus CRUD over the reviewed result.
 * <p>
 * Parsing and saving are deliberately separate operations — an abandoned parse
 * must not leave a row behind, and re-parsing to fix a bad paste must not create
 * a duplicate report.
 */
@Service
@RequiredArgsConstructor
public class CmReportService {

    private final CmReportRepository cmReportRepository;
    private final CmReportExtractionService cmReportExtractionService;
    private final UserRepository userRepository;
    private final CaseTicketRepository caseTicketRepository;
    private final CaseTicketUpdateRepository caseTicketUpdateRepository;

    private static final ZoneId BANGKOK = ZoneId.of("Asia/Bangkok");
    private static final DateTimeFormatter COMMENT_STAMP = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm");

    /**
     * Ceiling on a stored signature image. The client downscales to ~600px before
     * upload (tens of KB), so this only catches a client that skipped the resize —
     * it is the actual boundary, not the resize helper.
     */
    private static final int MAX_SIGNATURE_CHARS = 512 * 1024;

    /** Runs the AI extraction. Persists nothing — the caller reviews the draft first. */
    public CmReportDraft parse(String sourceText) {
        return cmReportExtractionService.extractCmReport(sourceText);
    }

    // ─── From a monday ticket ────────────────────────────────────────────────

    /**
     * The tickets a CM report can be started from: what the daily sync holds from
     * the All Case group of the Cleaning and Delivery boards, newest first.
     *
     * @param board CLEANING, DELIVERY or null for both
     * @param q     matches case id, ticket name, project, branch, serial or model
     */
    @Transactional(readOnly = true)
    public List<CmTicketSummary> tickets(CmTicketSummary.CmTicketBoard board, String q) {
        List<CaseTicket> tickets = new ArrayList<>();
        if (board != CmTicketSummary.CmTicketBoard.DELIVERY) {
            tickets.addAll(caseTicketRepository.findBySourceBoardIdAndIsPresentTrue(CaseTicketSyncService.CLEANING_BOARD));
        }
        if (board != CmTicketSummary.CmTicketBoard.CLEANING) {
            tickets.addAll(caseTicketRepository.findBySourceBoardIdAndIsPresentTrue(CaseTicketSyncService.DELIVERY_BOARD));
        }
        String needle = q == null ? "" : q.strip().toLowerCase(Locale.ROOT);
        if (!needle.isEmpty()) {
            tickets.removeIf(t -> !matches(t, needle));
        }
        if (tickets.isEmpty()) return List.of();

        // One query each for the thread sizes and the reports already written.
        Map<UUID, List<CaseTicketUpdate>> threads = caseTicketUpdateRepository
                .findByCaseTicketIdInOrderByPostedAtAsc(tickets.stream().map(CaseTicket::getId).toList())
                .stream().collect(Collectors.groupingBy(CaseTicketUpdate::getCaseTicketId));
        Set<String> reported = new HashSet<>(cmReportRepository.findExistingTicketNos(
                tickets.stream().map(CaseTicket::getSourceItemId).toList()));

        return tickets.stream()
                .map(t -> summary(t, threads.getOrDefault(t.getId(), List.of()), reported.contains(t.getSourceItemId())))
                .sorted(Comparator.comparing(CmTicketSummary::openDate, Comparator.nullsLast(Comparator.reverseOrder()))
                        .thenComparing(CmTicketSummary::caseId, Comparator.reverseOrder()))
                .toList();
    }

    /**
     * Draft a report from one ticket: its columns and its whole comment thread become
     * the source text, the extractor reads that, and then the facts the ticket states
     * outright overrule the model - the ticket number is the case id, the serial and
     * model are the board's, and the technician is whoever wrote the latest comment.
     */
    @Transactional(readOnly = true)
    public CmTicketDraft parseTicket(UUID caseTicketId) {
        CaseTicket ticket = caseTicketRepository.findById(caseTicketId)
                .orElseThrow(() -> new ResourceNotFoundException("Ticket " + caseTicketId + " is not in the synced boards."));
        List<CaseTicketUpdate> thread = caseTicketUpdateRepository.findByCaseTicketIdInOrderByPostedAtAsc(List.of(ticket.getId()));
        boolean reported = !cmReportRepository.findExistingTicketNos(List.of(ticket.getSourceItemId())).isEmpty();
        CmTicketSummary summary = summary(ticket, thread, reported);

        String sourceText = sourceTextOf(ticket, thread);
        CmReportDraft ai = cmReportExtractionService.extractCmReport(sourceText);

        CaseTicketUpdate latest = thread.isEmpty() ? null : thread.get(thread.size() - 1);
        String technician = latest != null && notBlank(latest.getCreatorName()) ? latest.getCreatorName().strip() : ai.technicianName();
        String reportDate = ai.reportDate();
        if (!notBlank(reportDate)) {
            LocalDate d = latest != null && latest.getPostedAt() != null ? bangkokDate(latest.getPostedAt()) : ticket.getOpenDate();
            reportDate = d == null ? null : d.toString();
        }
        CmReportDraft draft = new CmReportDraft(
                reportDate,
                ticket.getSourceItemId(),
                firstNonBlank(ai.customerName(), ticket.getProjectRaw(), ticket.getBranchRaw(), ticket.getItemName()),
                technician,
                firstNonBlank(ticket.getRobotModel(), ai.robotModel()),
                firstNonBlank(ticket.getSerialNumbers(), ai.serialNumber()),
                firstNonBlank(ai.causeDetail(), ticket.getMainIssue()),
                ai.inspectionResult(),
                ai.correctiveActions(),
                ai.testResult());
        return new CmTicketDraft(summary, sourceText, draft);
    }

    /**
     * The ticket as text, in the shape the staff used to paste: the board's columns
     * as labelled lines, then the comment thread oldest first with author and time,
     * so the extractor sees the story in order and the staff can read it back.
     */
    public static String sourceTextOf(CaseTicket t, List<CaseTicketUpdate> thread) {
        StringBuilder s = new StringBuilder();
        line(s, "Ticket No.", t.getSourceItemId());
        line(s, "Board", CaseTicketSyncService.DELIVERY_BOARD.equals(t.getSourceBoardId()) ? "Delivery Tickets" : "Cleaning Tickets");
        line(s, "Ticket", t.getItemName());
        line(s, "Open date", t.getOpenDate() == null ? null : t.getOpenDate().toString());
        line(s, "ชื่อบริษัทลูกค้า / Project", t.getProjectRaw());
        line(s, "สาขา / Branch", join(t.getBranchRaw(), t.getBranchCodeRaw()));
        line(s, "จังหวัด / Province", t.getProvinceRaw());
        line(s, "รุ่นหุ่นยนต์ / Robot model", t.getRobotModel());
        line(s, "Serial number", t.getSerialNumbers());
        line(s, "Status", join(t.getStatus(), t.getSupStatus()));
        line(s, "อาการ / Main issue", t.getMainIssue());
        line(s, "Solution", t.getSolution());
        line(s, "Re-action date", t.getReActionDate() == null ? null : t.getReActionDate().toString());
        s.append("\n--- Comments (").append(thread.size()).append(", oldest first) ---\n");
        for (CaseTicketUpdate u : thread) {
            if (!notBlank(u.getBody())) continue;
            s.append('[');
            if (u.getPostedAt() != null) s.append(COMMENT_STAMP.format(u.getPostedAt().atOffset(ZoneOffset.UTC).atZoneSameInstant(BANGKOK)));
            if (notBlank(u.getCreatorName())) s.append(u.getPostedAt() != null ? " " : "").append(u.getCreatorName().strip());
            if (u.getParentUpdateId() != null) s.append(" (reply)");
            s.append("]\n").append(u.getBody().strip()).append("\n\n");
        }
        return s.toString().strip();
    }

    private static CmTicketSummary summary(CaseTicket t, List<CaseTicketUpdate> thread, boolean hasReport) {
        LocalDateTime last = thread.isEmpty() ? null : thread.get(thread.size() - 1).getPostedAt();
        return new CmTicketSummary(
                t.getId(), t.getSourceItemId(),
                CaseTicketSyncService.DELIVERY_BOARD.equals(t.getSourceBoardId())
                        ? CmTicketSummary.CmTicketBoard.DELIVERY : CmTicketSummary.CmTicketBoard.CLEANING,
                t.getItemName(), t.getProjectRaw(), t.getBranchRaw(), t.getProvinceRaw(),
                t.getRobotModel(), t.getSerialNumbers(), t.getStatus(), t.getSupStatus(), t.getMainIssue(),
                t.getOpenDate(), thread.size(), last, hasReport);
    }

    private static boolean matches(CaseTicket t, String needle) {
        for (String v : new String[] {t.getSourceItemId(), t.getItemName(), t.getProjectRaw(), t.getBranchRaw(),
                t.getBranchCodeRaw(), t.getSerialNumbers(), t.getRobotModel(), t.getMainIssue()}) {
            if (v != null && v.toLowerCase(Locale.ROOT).contains(needle)) return true;
        }
        return false;
    }

    private static LocalDate bangkokDate(LocalDateTime utc) {
        return utc.atOffset(ZoneOffset.UTC).atZoneSameInstant(BANGKOK).toLocalDate();
    }

    private static void line(StringBuilder s, String label, String value) {
        if (notBlank(value)) s.append(label).append(" : ").append(value.strip()).append('\n');
    }

    private static String join(String a, String b) {
        if (!notBlank(a)) return notBlank(b) ? b : null;
        return notBlank(b) ? a + " / " + b : a;
    }

    private static String firstNonBlank(String... values) {
        for (String v : values) if (notBlank(v)) return v.strip();
        return null;
    }

    private static boolean notBlank(String v) {
        return v != null && !v.isBlank();
    }

    @Transactional(readOnly = true)
    public List<CmReportResponse> search(String keyword) {
        List<CmReport> found = (keyword == null || keyword.isBlank())
                ? cmReportRepository.findAllByOrderByCreatedAtDesc()
                : cmReportRepository.search(keyword.strip());
        return found.stream().map(CmReportResponse::summaryFrom).toList();
    }

    @Transactional(readOnly = true)
    public CmReportResponse getById(UUID id) {
        return CmReportResponse.from(require(id));
    }

    @Transactional
    public CmReportResponse create(CmReportRequest request, UUID createdBy) {
        CmReport report = CmReport.builder()
                .reportDate(request.reportDate())
                .ticketNo(trimToNull(request.ticketNo()))
                .customerName(request.customerName().strip())
                .technicianName(trimToNull(request.technicianName()))
                .robotModel(trimToNull(request.robotModel()))
                .serialNumber(trimToNull(request.serialNumber()))
                .causeDetail(trimToNull(request.causeDetail()))
                .inspectionResult(trimToNull(request.inspectionResult()))
                .correctiveActions(trimToNull(request.correctiveActions()))
                .testResult(trimToNull(request.testResult()))
                .sourceText(trimToNull(request.sourceText()))
                .providerSignature(validateSignature(request.providerSignature(), "ลงนามผู้ให้บริการ"))
                .receiverSignature(validateSignature(request.receiverSignature(), "ลงนามผู้รับบริการ"))
                .createdBy(createdBy == null ? null : userRepository.findById(createdBy).orElse(null))
                .build();
        return CmReportResponse.from(cmReportRepository.save(report));
    }

    @Transactional
    public CmReportResponse update(UUID id, CmReportRequest request) {
        CmReport report = require(id);
        report.setReportDate(request.reportDate());
        report.setTicketNo(trimToNull(request.ticketNo()));
        report.setCustomerName(request.customerName().strip());
        report.setTechnicianName(trimToNull(request.technicianName()));
        report.setRobotModel(trimToNull(request.robotModel()));
        report.setSerialNumber(trimToNull(request.serialNumber()));
        report.setCauseDetail(trimToNull(request.causeDetail()));
        report.setInspectionResult(trimToNull(request.inspectionResult()));
        report.setCorrectiveActions(trimToNull(request.correctiveActions()));
        report.setTestResult(trimToNull(request.testResult()));
        report.setSourceText(trimToNull(request.sourceText()));
        report.setProviderSignature(validateSignature(request.providerSignature(), "ลงนามผู้ให้บริการ"));
        report.setReceiverSignature(validateSignature(request.receiverSignature(), "ลงนามผู้รับบริการ"));
        return CmReportResponse.from(cmReportRepository.save(report));
    }

    @Transactional
    public void delete(UUID id) {
        cmReportRepository.delete(require(id));
    }

    private CmReport require(UUID id) {
        return cmReportRepository.findById(id)
                .orElseThrow(() -> new ResourceNotFoundException("CmReport", "id", id));
    }

    private static String trimToNull(String value) {
        if (value == null) return null;
        String trimmed = value.strip();
        return trimmed.isEmpty() ? null : trimmed;
    }

    /**
     * Signatures are stored inline in the row, so an un-resized phone photo would
     * bloat the table and every subsequent read of the report. Rejects anything that
     * is not an image data URI, or that exceeds {@link #MAX_SIGNATURE_CHARS}.
     */
    private static String validateSignature(String dataUri, String label) {
        String value = trimToNull(dataUri);
        if (value == null) return null;
        if (!value.startsWith("data:image/")) {
            throw new BadRequestException(
                    "Signature for '" + label + "' must be an uploaded image.");
        }
        if (value.length() > MAX_SIGNATURE_CHARS) {
            throw new BadRequestException(
                    "Signature image for '" + label + "' is too large. Please upload a smaller photo.");
        }
        return value;
    }
}
