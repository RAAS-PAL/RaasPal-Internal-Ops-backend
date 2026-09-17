package com.raaspal.robotrecommendation.kpi.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.raaspal.robotrecommendation.casereport.adapters.monday.dto.MondayColumnValue;
import com.raaspal.robotrecommendation.casereport.adapters.monday.dto.MondayItem;
import com.raaspal.robotrecommendation.kpi.config.KpiMondayProperties;
import com.raaspal.robotrecommendation.kpi.entity.KpiCaseTicket;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Turns a monday board row into a {@link KpiCaseTicket} using one board's column
 * mapping. Pure: no I/O, so the mapping rules are unit-tested directly.
 *
 * <p>The rules that are not obvious:
 * <ul>
 *   <li>A date column's {@code text} is {@code YYYY-MM-DD}, optionally followed
 *       by a time; only the date is kept. Anything unparseable becomes null
 *       rather than failing the sync — one malformed cell must not stop the
 *       other 1,400 tickets from landing.</li>
 *   <li>Serials are split on the separators seen live on the boards ({@code /}
 *       on Cleaning, {@code และ} on Delivery, plus the usual list separators),
 *       stripped of a leading {@code #}, closed up and upper-cased. The raw
 *       text is kept alongside.</li>
 *   <li>{@code closed} is true when a close date is present or the status is one
 *       the board config lists as finished. Without either, the ticket is open
 *       as far as the KPI knows.</li>
 * </ul>
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class CaseTicketMapper {

    /** Joins the normalised serials in {@link KpiCaseTicket#getSerialsNormalised()}. */
    public static final String SERIAL_JOIN = "|";

    private static final Pattern SERIAL_SPLIT = Pattern.compile("\\s*(?:/|,|;|&|\\R|และ)\\s*");
    private static final Pattern ISO_DATE = Pattern.compile("\\d{4}-\\d{2}-\\d{2}");

    private final ObjectMapper objectMapper;

    /** A ticket seen for the first time. */
    public KpiCaseTicket newTicket(MondayItem item, KpiMondayProperties.Board board, LocalDateTime now) {
        KpiCaseTicket ticket = KpiCaseTicket.builder()
                .source(KpiCaseTicket.SOURCE_MONDAY)
                .sourceBoardId(board.getId())
                .sourceItemId(item.id())
                .serviceLine(board.getServiceLine())
                .firstSeenAt(now)
                .build();
        apply(item, board, ticket, now);
        return ticket;
    }

    /** Re-reads every mapped field from the row onto an existing ticket. */
    public void apply(MondayItem item, KpiMondayProperties.Board board, KpiCaseTicket ticket, LocalDateTime now) {
        KpiMondayProperties.Columns columns = board.getColumns();

        ticket.setSourceGroupId(item.group() == null ? null : item.group().id());
        ticket.setSourceGroupTitle(item.groupTitle());
        ticket.setTicketType(board.getTicketType());
        // A board either states its line or names the column that does; the
        // installation board carries both robot types on one board.
        ticket.setServiceLine(board.resolveServiceLine(text(item, board.getServiceLineColumn())));
        ticket.setItemName(item.name());

        ticket.setTicketNo(text(item, columns.getTicketNo()));
        ticket.setProjectRaw(text(item, columns.getProject()));
        ticket.setBranchRaw(text(item, columns.getBranch()));
        ticket.setBranchCodeRaw(text(item, columns.getBranchCode()));
        ticket.setProvinceRaw(text(item, columns.getProvince()));
        ticket.setRobotModel(text(item, columns.getRobotModel()));
        ticket.setStatus(text(item, columns.getStatus()));
        ticket.setSupStatus(text(item, columns.getSupStatus()));
        ticket.setIssueLevel(text(item, columns.getIssueLevel()));
        ticket.setMainIssue(text(item, columns.getMainIssue()));
        ticket.setCategory(text(item, columns.getCategory()));

        String serials = text(item, columns.getSerial());
        ticket.setSerialNumbers(serials);
        ticket.setSerialsNormalised(joinSerials(normaliseSerials(serials)));

        ticket.setOpenDate(parseDate(text(item, columns.getOpenDate()), item.id(), columns.getOpenDate()));
        ticket.setCloseDate(parseDate(text(item, columns.getCloseDate()), item.id(), columns.getCloseDate()));
        ticket.setActionDate(parseDate(text(item, columns.getActionDate()), item.id(), columns.getActionDate()));
        ticket.setInstallDate(parseTimelineEnd(text(item, columns.getInstallDate())));
        ticket.setClosed(ticket.getCloseDate() != null || board.isClosedStatus(ticket.getStatus()));

        ticket.setRawColumns(rawColumnsJson(item));
        ticket.setSourceUpdatedAt(toUtc(item.updatedAt()));
        ticket.setLastSyncedAt(now);
        ticket.setPresent(true);
    }

    /** The cell text for a mapped column, or null when the field is unmapped or the cell is blank. */
    private static String text(MondayItem item, String columnId) {
        return columnId == null || columnId.isBlank() ? null : item.columnText(columnId);
    }

    /** Only the date part is meaningful: monday appends a time when the column has one enabled. */
    static LocalDate parseDate(String text, String itemId, String columnId) {
        if (text == null || text.isBlank()) {
            return null;
        }
        String datePart = text.strip();
        if (datePart.length() > 10) {
            datePart = datePart.substring(0, 10);
        }
        try {
            return LocalDate.parse(datePart);
        } catch (DateTimeParseException e) {
            log.debug("Ignoring unparseable date '{}' in column {} of monday item {}", text, columnId, itemId);
            return null;
        }
    }

    /**
     * The LATER date in a TimeLine cell — when the work finished, which is what
     * the 30-day first-time-install window is measured from.
     *
     * <p>monday renders a timeline as {@code "2026-08-01 - 2026-08-05"}, but the
     * same column can hold a single date, and some boards use an en dash. Rather
     * than parse the separator, every {@code YYYY-MM-DD} in the text is collected
     * and the latest is taken, so all three shapes work and the order the board
     * writes them in does not matter.
     */
    public static LocalDate parseTimelineEnd(String text) {
        if (text == null || text.isBlank()) {
            return null;
        }
        Matcher matcher = ISO_DATE.matcher(text);
        LocalDate latest = null;
        while (matcher.find()) {
            try {
                LocalDate found = LocalDate.parse(matcher.group());
                if (latest == null || found.isAfter(latest)) {
                    latest = found;
                }
            } catch (DateTimeParseException e) {
                // A number that looks like a date but is not one (2026-13-40).
                log.debug("Ignoring unparseable timeline date '{}'", matcher.group());
            }
        }
        return latest;
    }

    /** monday reports {@code updated_at} with an offset; stored in UTC so comparisons are stable. */
    static LocalDateTime toUtc(java.time.OffsetDateTime value) {
        return value == null ? null : value.withOffsetSameInstant(ZoneOffset.UTC).toLocalDateTime();
    }

    /**
     * The serials named in a cell, normalised for joining: split on the list
     * separators the boards use, leading {@code #} dropped, internal whitespace
     * removed ({@code Pudu 1} → {@code PUDU1}), upper-cased, de-duplicated,
     * original order kept.
     */
    public static List<String> normaliseSerials(String raw) {
        if (raw == null || raw.isBlank()) {
            return List.of();
        }
        LinkedHashSet<String> out = new LinkedHashSet<>();
        for (String part : SERIAL_SPLIT.split(raw)) {
            String serial = part.strip();
            if (serial.startsWith("#")) {
                serial = serial.substring(1);
            }
            serial = serial.replaceAll("\\s+", "").toUpperCase(Locale.ROOT);
            if (!serial.isEmpty()) {
                out.add(serial);
            }
        }
        return List.copyOf(out);
    }

    public static String joinSerials(List<String> serials) {
        return serials.isEmpty() ? null : String.join(SERIAL_JOIN, serials);
    }

    /** Inverse of {@link #joinSerials}. */
    public static List<String> splitSerials(String stored) {
        return stored == null || stored.isBlank() ? List.of() : List.of(stored.split(Pattern.quote(SERIAL_JOIN)));
    }

    /** Every cell as {@code {id, title, type, text}}, so the archive is readable without the board. */
    private String rawColumnsJson(MondayItem item) {
        List<Map<String, String>> cells = new ArrayList<>();
        if (item.columnValues() != null) {
            for (MondayColumnValue value : item.columnValues()) {
                Map<String, String> cell = new LinkedHashMap<>();
                cell.put("id", value.id());
                cell.put("title", value.title());
                cell.put("type", value.type());
                cell.put("text", value.text());
                cells.add(cell);
            }
        }
        try {
            return objectMapper.writeValueAsString(cells);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("Could not serialise monday columns for item " + item.id(), e);
        }
    }
}
