package com.raaspal.robotrecommendation.casereport.brand;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.raaspal.robotrecommendation.casereport.brand.dto.BrandTicket;
import com.raaspal.robotrecommendation.casereport.entity.CaseSource;
import com.raaspal.robotrecommendation.casereport.entity.CaseTicket;
import com.raaspal.robotrecommendation.casereport.entity.CaseTicketUpdate;
import com.raaspal.robotrecommendation.casereport.repository.CaseTicketRepository;
import com.raaspal.robotrecommendation.casereport.repository.CaseTicketUpdateRepository;
import com.raaspal.robotrecommendation.casereport.service.CaseTicketSyncService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.time.ZoneId;
import java.time.temporal.ChronoUnit;
import java.util.*;

/**
 * Reads one brand's tickets out of {@code case_ticket} and shapes them for the page.
 *
 * <p>Filtering happens in memory on purpose. The board's rows in the table number in
 * the low thousands, the matcher is two string tests, and keeping it here means the API
 * filter, the stored-row filter and the tests all share one definition of a brand.
 *
 * <p>Threads are not loaded with the list. Gausium alone is 2,600 tickets and 10,000
 * comments; the summary needs none of them, the page opens one thread at a time
 * ({@link #thread}), and only the export wants them all ({@link #withThreads}).
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class BrandTicketQueryService {

    /** Dates on the board are Bangkok dates; ages are counted against Bangkok's today. */
    static final ZoneId BUSINESS_ZONE = ZoneId.of("Asia/Bangkok");

    /** Largest ticket-id list sent in one {@code IN} when loading threads. */
    private static final int THREAD_CHUNK = 500;

    /**
     * The unmapped columns the page shows, read back out of {@code raw_columns}. Same
     * fields on both boards under different ids - {@code status_1} is Sup Status on
     * delivery and Issue Level on cleaning - so they are looked up per board, never
     * shared. RE is a status column on delivery and a people column on cleaning; both
     * arrive as text.
     */
    record RawColumns(String rootCause, String reOwner, String caseType, String level,
                      String underWarranty, String channel) {

        static final RawColumns DELIVERY = new RawColumns(
                "status_10", "color_mksn4t14", "color_mkyh88bs", "status_136", "text23", "status_169");
        static final RawColumns CLEANING = new RawColumns(
                "status6", "people3", "color_mkyj4ncq", "status_1", "status_15", "status08");

        static RawColumns forBoard(String boardId) {
            return CaseTicketSyncService.CLEANING_BOARD.equals(boardId) ? CLEANING : DELIVERY;
        }
    }

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
     * Every stored ticket of the brand, newest first, with comment counts but not the
     * comments themselves.
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

        Map<UUID, Integer> commentCounts = new HashMap<>();
        if (!rows.isEmpty()) {
            for (Object[] count : updates.countByTicketOnBoard(brand.getBoardId())) {
                commentCounts.put((UUID) count[0], ((Number) count[1]).intValue());
            }
        }

        RawColumns columns = RawColumns.forBoard(brand.getBoardId());
        LocalDate today = LocalDate.now(BUSINESS_ZONE);
        return rows.stream()
                .map(row -> toDto(row, commentCounts.getOrDefault(row.getId(), 0), today, brand, columns))
                .sorted(Comparator.comparing(BrandTicket::openDate,
                                Comparator.nullsLast(Comparator.reverseOrder()))
                        .thenComparing(BrandTicket::lastSyncedAt, Comparator.nullsLast(Comparator.reverseOrder())))
                .toList();
    }

    /** The same tickets with their whole threads, for the export. */
    @Transactional(readOnly = true)
    public List<BrandTicket> withThreads(List<BrandTicket> list) {
        List<UUID> ids = list.stream().map(t -> UUID.fromString(t.id())).toList();
        Map<UUID, List<CaseTicketUpdate>> threads = new HashMap<>();
        for (int i = 0; i < ids.size(); i += THREAD_CHUNK) {
            updates.findByCaseTicketIdInOrderByPostedAtAsc(ids.subList(i, Math.min(i + THREAD_CHUNK, ids.size())))
                    .forEach(u -> threads.computeIfAbsent(u.getCaseTicketId(), id -> new ArrayList<>()).add(u));
        }
        return list.stream()
                .map(t -> {
                    List<BrandTicket.Comment> thread = toComments(threads.getOrDefault(UUID.fromString(t.id()), List.of()));
                    return t.toBuilder().comments(thread).commentCount(thread.size()).build();
                })
                .toList();
    }

    /**
     * One ticket's thread, oldest first.
     *
     * @throws NoSuchElementException when the ticket is not one of this brand's - the
     *                                id alone must not open another brand's thread
     */
    @Transactional(readOnly = true)
    public List<BrandTicket.Comment> thread(BrandTicketProperties.Brand brand, UUID ticketId) {
        BrandMatcher matcher = new BrandMatcher(brand);
        tickets.findById(ticketId)
                .filter(row -> brand.getBoardId().equals(row.getSourceBoardId()) && matcher.matches(row))
                .orElseThrow(() -> new NoSuchElementException("No " + brand.getKey() + " ticket " + ticketId));
        return toComments(updates.findByCaseTicketIdInOrderByPostedAtAsc(List.of(ticketId)));
    }

    /** The date a ticket is filed under: its Open Date, else the day the sync first saw it. */
    public static LocalDate ticketDate(BrandTicket t) {
        return t.openDate() != null ? t.openDate()
                : t.firstSeenAt() != null ? t.firstSeenAt().toLocalDate() : null;
    }

    /**
     * Whether a ticket counts as open, by the brand's rule.
     *
     * <p>{@code NOT_DONE} (AutoXing): not in a Done group and not carrying the Done
     * status - wider than the pending reports' "in the All Case group", because the
     * Check and On Hold groups hold cases still being worked.
     *
     * <p>{@code OPEN_GROUP} (Gausium): in the open group, which is what
     * {@code is_present} records - both syncs set it from the group, the daily one by
     * absence.
     */
    static boolean isOpen(CaseTicket row, BrandTicketProperties.OpenRule rule) {
        if (rule == BrandTicketProperties.OpenRule.OPEN_GROUP) return row.isPresent();
        String group = row.getSourceGroupTitle() == null ? "" : row.getSourceGroupTitle().trim().toLowerCase(Locale.ROOT);
        String status = row.getStatus() == null ? "" : row.getStatus().trim().toLowerCase(Locale.ROOT);
        return !group.startsWith("done") && !status.equals("done");
    }

    private BrandTicket toDto(CaseTicket row, int commentCount, LocalDate today,
                              BrandTicketProperties.Brand brand, RawColumns columns) {
        Map<String, String> raw = readRaw(row.getRawColumns());
        boolean open = isOpen(row, brand.getOpenRule());
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
                .rootCause(blankToNull(raw.get(columns.rootCause())))
                .reOwner(blankToNull(raw.get(columns.reOwner())))
                .caseType(blankToNull(raw.get(columns.caseType())))
                .level(blankToNull(raw.get(columns.level())))
                .underWarranty(blankToNull(raw.get(columns.underWarranty())))
                .channel(blankToNull(raw.get(columns.channel())))
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
                .commentCount(commentCount)
                .comments(List.of())
                .build();
    }

    private static List<BrandTicket.Comment> toComments(List<CaseTicketUpdate> thread) {
        return thread.stream().map(u -> BrandTicket.Comment.builder()
                .id(u.getSourceUpdateId())
                .parentId(u.getParentUpdateId())
                .author(u.getCreatorName())
                .postedAt(u.getPostedAt())
                .body(u.getBody())
                .build()).toList();
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
