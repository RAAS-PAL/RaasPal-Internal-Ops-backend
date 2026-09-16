package com.raaspal.robotrecommendation.casereport.brand;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.raaspal.robotrecommendation.casereport.brand.dto.BrandTicket;
import com.raaspal.robotrecommendation.casereport.entity.CaseSource;
import com.raaspal.robotrecommendation.casereport.entity.CaseTicket;
import com.raaspal.robotrecommendation.casereport.entity.CaseTicketUpdate;
import com.raaspal.robotrecommendation.casereport.repository.CaseTicketRepository;
import com.raaspal.robotrecommendation.casereport.repository.CaseTicketUpdateRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.time.ZoneId;
import java.time.temporal.ChronoUnit;
import java.util.*;
import java.util.stream.Collectors;

/**
 * Reads one brand's tickets out of {@code case_ticket} and shapes them for the page.
 *
 * <p>Filtering happens in memory on purpose. The board's rows in the table number in
 * the hundreds, the matcher is two string tests, and keeping it here means the API
 * filter, the stored-row filter and the tests all share one definition of "AutoXing".
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class BrandTicketQueryService {

    /** Dates on the board are Bangkok dates; ages are counted against Bangkok's today. */
    static final ZoneId BUSINESS_ZONE = ZoneId.of("Asia/Bangkok");

    /** Unmapped delivery-board columns, read back out of {@code raw_columns}. */
    private static final String ROOT_CAUSE = "status_10";
    private static final String RE_OWNER = "color_mksn4t14";
    private static final String CASE_TYPE = "color_mkyh88bs";
    private static final String LEVEL = "status_136";
    private static final String UNDER_WARRANTY = "text23";
    private static final String CHANNEL = "status_169";

    private static final TypeReference<Map<String, String>> RAW = new TypeReference<>() {
    };

    private final BrandTicketProperties properties;
    private final CaseTicketRepository tickets;
    private final CaseTicketUpdateRepository updates;
    private final ObjectMapper objectMapper;

    public BrandTicketProperties.Brand brand(String key) {
        return properties.find(key)
                .orElseThrow(() -> new NoSuchElementException("Unknown ticket brand: " + key));
    }

    /**
     * Every stored ticket of the brand, newest first, with threads attached.
     *
     * <p>No date range here: the summary needs the whole set to answer "open now" and to
     * find a repeat that falls just outside the window. Callers slice it.
     */
    @Transactional(readOnly = true)
    public List<BrandTicket> all(BrandTicketProperties.Brand brand) {
        BrandMatcher matcher = new BrandMatcher(brand);
        List<CaseTicket> rows = tickets.findBySourceAndSourceBoardId(CaseSource.MONDAY, brand.getBoardId())
                .stream()
                .filter(matcher::matches)
                .toList();

        Map<UUID, List<CaseTicketUpdate>> threads = rows.isEmpty()
                ? Map.of()
                : updates.findByCaseTicketIdInOrderByPostedAtAsc(rows.stream().map(CaseTicket::getId).toList())
                        .stream()
                        .collect(Collectors.groupingBy(CaseTicketUpdate::getCaseTicketId));

        LocalDate today = LocalDate.now(BUSINESS_ZONE);
        return rows.stream()
                .map(row -> toDto(row, threads.getOrDefault(row.getId(), List.of()), today, brand))
                .sorted(Comparator.comparing(BrandTicket::openDate,
                                Comparator.nullsLast(Comparator.reverseOrder()))
                        .thenComparing(BrandTicket::lastSyncedAt, Comparator.nullsLast(Comparator.reverseOrder())))
                .toList();
    }

    /** The date a ticket is filed under: its Open Date, else the day the sync first saw it. */
    public static LocalDate ticketDate(BrandTicket t) {
        return t.openDate() != null ? t.openDate()
                : t.firstSeenAt() != null ? t.firstSeenAt().toLocalDate() : null;
    }

    /**
     * Open means "not finished": not in a Done group and not carrying the Done status.
     *
     * <p>Wider than the pending reports' "in the All Case group": the Check and On Hold
     * groups hold cases still being worked, and a brand review wants those counted as
     * open, not as vanished.
     */
    static boolean isOpen(CaseTicket row) {
        String group = row.getSourceGroupTitle() == null ? "" : row.getSourceGroupTitle().trim().toLowerCase(Locale.ROOT);
        String status = row.getStatus() == null ? "" : row.getStatus().trim().toLowerCase(Locale.ROOT);
        return !group.startsWith("done") && !status.equals("done");
    }

    private BrandTicket toDto(CaseTicket row, List<CaseTicketUpdate> thread, LocalDate today,
                              BrandTicketProperties.Brand brand) {
        Map<String, String> raw = readRaw(row.getRawColumns());
        boolean open = isOpen(row);
        LocalDate openDate = row.getOpenDate();
        LocalDate action = row.getReActionDate();

        Integer daysToAction = openDate != null && action != null && !action.isBefore(openDate)
                ? (int) ChronoUnit.DAYS.between(openDate, action) : null;
        Integer ageDays = open && openDate != null && !openDate.isAfter(today)
                ? (int) ChronoUnit.DAYS.between(openDate, today) : null;

        return BrandTicket.builder()
                .id(row.getId().toString())
                .itemId(row.getSourceItemId())
                .name(row.getItemName())
                .group(row.getSourceGroupTitle())
                .open(open)
                .status(row.getStatus())
                .supStatus(row.getSupStatus())
                .project(row.getProjectRaw())
                .branch(row.getBranchRaw())
                .branchCode(row.getBranchCodeRaw())
                .province(row.getProvinceRaw())
                .model(row.getRobotModel())
                .serial(normaliseSerial(row.getSerialNumbers()))
                .rootCause(blankToNull(raw.get(ROOT_CAUSE)))
                .reOwner(blankToNull(raw.get(RE_OWNER)))
                .caseType(blankToNull(raw.get(CASE_TYPE)))
                .level(blankToNull(raw.get(LEVEL)))
                .underWarranty(blankToNull(raw.get(UNDER_WARRANTY)))
                .channel(blankToNull(raw.get(CHANNEL)))
                .mainIssue(row.getMainIssue())
                .solution(row.getSolution())
                .openDate(openDate)
                .reActionDate(action)
                .daysToAction(daysToAction)
                .ageDays(ageDays)
                .sourceUpdatedAt(row.getSourceUpdatedAt())
                .firstSeenAt(row.getFirstSeenAt())
                .lastSyncedAt(row.getLastSyncedAt())
                .mondayUrl(mondayUrl(brand, row.getSourceItemId()))
                .comments(thread.stream().map(u -> BrandTicket.Comment.builder()
                        .id(u.getSourceUpdateId())
                        .parentId(u.getParentUpdateId())
                        .author(u.getCreatorName())
                        .postedAt(u.getPostedAt())
                        .body(u.getBody())
                        .build()).toList())
                .build();
    }

    private Map<String, String> readRaw(String json) {
        if (json == null || json.isBlank()) return Map.of();
        try {
            return objectMapper.readValue(json, RAW);
        } catch (Exception e) {
            log.warn("Unreadable raw_columns payload: {}", e.getMessage());
            return Map.of();
        }
    }

    private String mondayUrl(BrandTicketProperties.Brand brand, String itemId) {
        String base = properties.getMondayWebUrl();
        if (base == null || base.isBlank() || itemId == null) return null;
        return base.replaceAll("/+$", "") + "/boards/" + brand.getBoardId() + "/pulses/" + itemId;
    }

    /**
     * The serial as typed, trimmed and upper-cased so the same robot written twice
     * counts once. A tags cell can hold several; the first is the robot the ticket
     * is about.
     */
    static String normaliseSerial(String serials) {
        if (serials == null) return null;
        String first = serials.split(",")[0].trim().toUpperCase(Locale.ROOT);
        return first.isEmpty() ? null : first;
    }

    private static String blankToNull(String s) {
        return s == null || s.isBlank() ? null : s.trim();
    }
}
