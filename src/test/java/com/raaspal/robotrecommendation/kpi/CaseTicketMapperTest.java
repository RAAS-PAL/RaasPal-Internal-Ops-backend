package com.raaspal.robotrecommendation.kpi;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.raaspal.robotrecommendation.casereport.adapters.monday.dto.MondayColumnRef;
import com.raaspal.robotrecommendation.casereport.adapters.monday.dto.MondayColumnValue;
import com.raaspal.robotrecommendation.casereport.adapters.monday.dto.MondayGroup;
import com.raaspal.robotrecommendation.casereport.adapters.monday.dto.MondayItem;
import com.raaspal.robotrecommendation.kpi.config.KpiMondayProperties;
import com.raaspal.robotrecommendation.kpi.entity.CaseTicket;
import com.raaspal.robotrecommendation.kpi.entity.ServiceLine;
import com.raaspal.robotrecommendation.kpi.entity.TicketType;
import com.raaspal.robotrecommendation.kpi.service.CaseTicketMapper;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Pins how a monday row becomes a {@link CaseTicket} under one board's column
 * mapping — the rules the KPI maths then depends on. Pure, no Spring.
 */
class CaseTicketMapperTest {

    private static final LocalDateTime NOW = LocalDateTime.of(2026, 9, 8, 1, 30);

    private final ObjectMapper objectMapper = new ObjectMapper();
    private final CaseTicketMapper mapper = new CaseTicketMapper(objectMapper);

    /** The Cleaning Tickets mapping from the test properties, built by hand. */
    private static KpiMondayProperties.Board cleaningBoard() {
        KpiMondayProperties.Board board = new KpiMondayProperties.Board();
        board.setId("3451717331");
        board.setServiceLine(ServiceLine.CLEANING);
        board.setTicketType(TicketType.CM);
        board.setClosedStatuses(List.of("Done", "ปิดงาน"));
        KpiMondayProperties.Columns columns = board.getColumns();
        columns.setOpenDate("date8");
        columns.setActionDate("date_1");
        columns.setCategory("color_mkyj4ncq");
        columns.setCloseDate("date_done");
        columns.setStatus("status");
        columns.setIssueLevel("status_1");
        columns.setMainIssue("text");
        columns.setSerial("text0");
        columns.setBranch("text6");
        return board;
    }

    private static MondayItem item(String id, OffsetDateTime updatedAt, Map<String, String> cells) {
        List<MondayColumnValue> values = new ArrayList<>();
        cells.forEach((columnId, text) ->
                values.add(new MondayColumnValue(columnId, "text", text, new MondayColumnRef(columnId, "Title of " + columnId))));
        return new MondayItem(id, "Ticket " + id, updatedAt, new MondayGroup("new_group96592__1", "All Case"), values, List.of());
    }

    private static Map<String, String> cells(String... keyValues) {
        Map<String, String> map = new LinkedHashMap<>();
        for (int i = 0; i < keyValues.length; i += 2) {
            map.put(keyValues[i], keyValues[i + 1]);
        }
        return map;
    }

    @Test
    void mappedColumnsLandOnTheirFieldsAndUnmappedOnesStayNull() {
        MondayItem item = item("1", OffsetDateTime.parse("2026-08-24T10:00:00+07:00"), cells(
                "date8", "2026-08-20",
                "status", "Working on it",
                "status_1", "Level 2",
                "text", "แขนหุ่นยนต์กดแล้วแขนไม่เด้ง",
                "text6", "Makro สุรินทร์",
                "text0", "GS438-6260-B9R-V300",
                "text_unmapped", "ignored"));

        CaseTicket ticket = mapper.newTicket(item, cleaningBoard(), NOW);

        assertThat(ticket.getSource()).isEqualTo(CaseTicket.SOURCE_MONDAY);
        assertThat(ticket.getSourceBoardId()).isEqualTo("3451717331");
        assertThat(ticket.getSourceItemId()).isEqualTo("1");
        assertThat(ticket.getServiceLine()).isEqualTo(ServiceLine.CLEANING);
        assertThat(ticket.getSourceGroupId()).isEqualTo("new_group96592__1");
        assertThat(ticket.getSourceGroupTitle()).isEqualTo("All Case");
        assertThat(ticket.getItemName()).isEqualTo("Ticket 1");
        assertThat(ticket.getOpenDate()).isEqualTo(LocalDate.of(2026, 8, 20));
        assertThat(ticket.getCloseDate()).isNull();
        assertThat(ticket.getStatus()).isEqualTo("Working on it");
        assertThat(ticket.getIssueLevel()).isEqualTo("Level 2");
        assertThat(ticket.getMainIssue()).isEqualTo("แขนหุ่นยนต์กดแล้วแขนไม่เด้ง");
        assertThat(ticket.getBranchRaw()).isEqualTo("Makro สุรินทร์");
        assertThat(ticket.getSerialNumbers()).isEqualTo("GS438-6260-B9R-V300");
        assertThat(ticket.getSerialsNormalised()).isEqualTo("GS438-6260-B9R-V300");
        // Not mapped on this board.
        assertThat(ticket.getTicketNo()).isNull();
        assertThat(ticket.getProjectRaw()).isNull();
        assertThat(ticket.getProvinceRaw()).isNull();
        assertThat(ticket.getSupStatus()).isNull();
        assertThat(ticket.getTicketType()).isEqualTo(TicketType.CM);
        assertThat(ticket.getCategory()).isNull();   // column absent on this item
        assertThat(ticket.isClosed()).isFalse();
        assertThat(ticket.isPresent()).isTrue();
        assertThat(ticket.getFirstSeenAt()).isEqualTo(NOW);
        assertThat(ticket.getLastSyncedAt()).isEqualTo(NOW);
    }

    @Test
    void updatedAtIsStoredInUtc() {
        MondayItem item = item("1", OffsetDateTime.parse("2026-08-24T10:00:00+07:00"), cells());

        CaseTicket ticket = mapper.newTicket(item, cleaningBoard(), NOW);

        assertThat(ticket.getSourceUpdatedAt()).isEqualTo(LocalDateTime.of(2026, 8, 24, 3, 0));
    }

    /** The separators seen live: '/' on Cleaning, 'และ' on Delivery, and a '#' prefix on asset columns. */
    @Test
    void serialsAreSplitStrippedAndUpperCased() {
        MondayItem item = item("1", null, cells("text0", "#GS438-6260-H7R-J000 / L352507605060zK และ Pudu 1"));

        CaseTicket ticket = mapper.newTicket(item, cleaningBoard(), NOW);

        assertThat(ticket.getSerialNumbers()).isEqualTo("#GS438-6260-H7R-J000 / L352507605060zK และ Pudu 1");
        assertThat(ticket.getSerialsNormalised()).isEqualTo("GS438-6260-H7R-J000|L352507605060ZK|PUDU1");
        assertThat(CaseTicketMapper.splitSerials(ticket.getSerialsNormalised()))
                .containsExactly("GS438-6260-H7R-J000", "L352507605060ZK", "PUDU1");
    }

    @Test
    void serialNormalisationEdges() {
        assertThat(CaseTicketMapper.normaliseSerials(null)).isEmpty();
        assertThat(CaseTicketMapper.normaliseSerials("   ")).isEmpty();
        assertThat(CaseTicketMapper.normaliseSerials("abc, ABC ,abc")).containsExactly("ABC");
        assertThat(CaseTicketMapper.normaliseSerials("a\nb; c & d")).containsExactly("A", "B", "C", "D");
        assertThat(CaseTicketMapper.joinSerials(List.of())).isNull();
        assertThat(CaseTicketMapper.splitSerials(null)).isEmpty();
    }

    @Test
    void closedByFinishedStatusWhenThereIsNoCloseDate() {
        MondayItem item = item("1", null, cells("date8", "2026-08-20", "status", "ปิดงาน"));

        CaseTicket ticket = mapper.newTicket(item, cleaningBoard(), NOW);

        assertThat(ticket.isClosed()).isTrue();
        assertThat(ticket.getCloseDate()).isNull();
    }

    @Test
    void closedStatusMatchIsCaseInsensitiveAndTrimmed() {
        MondayItem item = item("1", null, cells("status", "  done "));

        assertThat(mapper.newTicket(item, cleaningBoard(), NOW).isClosed()).isTrue();
    }

    @Test
    void closedByCloseDateWhateverTheStatusSays() {
        MondayItem item = item("1", null, cells("date8", "2026-08-20", "date_done", "2026-08-22", "status", "Working on it"));

        CaseTicket ticket = mapper.newTicket(item, cleaningBoard(), NOW);

        assertThat(ticket.isClosed()).isTrue();
        assertThat(ticket.getCloseDate()).isEqualTo(LocalDate.of(2026, 8, 22));
    }

    @Test
    void dateWithTimeKeepsOnlyTheDate() {
        MondayItem item = item("1", null, cells("date8", "2026-08-24 14:30"));

        assertThat(mapper.newTicket(item, cleaningBoard(), NOW).getOpenDate()).isEqualTo(LocalDate.of(2026, 8, 24));
    }

    /** One malformed cell must not stop the other 1,400 tickets from landing. */
    @Test
    void unparseableDateBecomesNullRatherThanFailingTheSync() {
        MondayItem item = item("1", null, cells("date8", "24/08/2026"));

        assertThat(mapper.newTicket(item, cleaningBoard(), NOW).getOpenDate()).isNull();
    }

    @Test
    void everyCellIsArchivedWithItsTitle() throws Exception {
        MondayItem item = item("1", null, cells("date8", "2026-08-20", "text_unmapped", "kept anyway"));

        CaseTicket ticket = mapper.newTicket(item, cleaningBoard(), NOW);

        JsonNode archive = objectMapper.readTree(ticket.getRawColumns());
        assertThat(archive.isArray()).isTrue();
        assertThat(archive).hasSize(2);
        assertThat(archive.get(1).path("id").asText()).isEqualTo("text_unmapped");
        assertThat(archive.get(1).path("title").asText()).isEqualTo("Title of text_unmapped");
        assertThat(archive.get(1).path("text").asText()).isEqualTo("kept anyway");
    }

    @Test
    void applyRefreshesAnExistingTicketWithoutTouchingFirstSeen() {
        KpiMondayProperties.Board board = cleaningBoard();
        LocalDateTime firstSeen = NOW.minusDays(3);
        CaseTicket ticket = mapper.newTicket(item("1", null, cells("status", "Working on it")), board, firstSeen);
        ticket.setPresent(false);

        mapper.apply(item("1", null, cells("status", "Done")), board, ticket, NOW);

        assertThat(ticket.getStatus()).isEqualTo("Done");
        assertThat(ticket.isClosed()).isTrue();
        assertThat(ticket.isPresent()).isTrue();
        assertThat(ticket.getFirstSeenAt()).isEqualTo(firstSeen);
        assertThat(ticket.getLastSyncedAt()).isEqualTo(NOW);
    }

    /** The reader now asks for {@code column { id title }}; the DTO must accept that shape. */
    @Test
    void columnValueParsesTheNestedColumnTitle() throws Exception {
        String json = """
                {"id":"date8","type":"date","text":"2026-08-20","column":{"id":"date8","title":"Open Date"}}
                """;

        MondayColumnValue value = objectMapper.readValue(json, MondayColumnValue.class);

        assertThat(value.title()).isEqualTo("Open Date");
        assertThat(new MondayColumnValue("x", "text", "v", null).title()).isNull();
    }

    /** The TimeLine column holds a range; the 30-day window starts at its LATER date. */
    @Test
    void timelineEndIsTheLaterDate() {
        assertThat(CaseTicketMapper.parseTimelineEnd("2026-08-01 - 2026-08-05")).isEqualTo(LocalDate.of(2026, 8, 5));
        // en dash, and the dates the other way round
        assertThat(CaseTicketMapper.parseTimelineEnd("2026-08-09 – 2026-08-02")).isEqualTo(LocalDate.of(2026, 8, 9));
        // a single date is a valid timeline of one day
        assertThat(CaseTicketMapper.parseTimelineEnd("2026-08-04")).isEqualTo(LocalDate.of(2026, 8, 4));
        assertThat(CaseTicketMapper.parseTimelineEnd(null)).isNull();
        assertThat(CaseTicketMapper.parseTimelineEnd("")).isNull();
        assertThat(CaseTicketMapper.parseTimelineEnd("not a date")).isNull();
    }

    /** The RE Action date is what SLA is measured to, so it must survive mapping. */
    @Test
    void actionDateIsMapped() {
        MondayItem item = item("1", null, cells("date8", "2026-08-20", "date_1", "2026-08-24"));

        CaseTicket ticket = mapper.newTicket(item, cleaningBoard(), NOW);

        assertThat(ticket.getOpenDate()).isEqualTo(LocalDate.of(2026, 8, 20));
        assertThat(ticket.getActionDate()).isEqualTo(LocalDate.of(2026, 8, 24));
    }

    /** An installation board carries both robot types; the column decides the line. */
    @Test
    void serviceLineCanComeFromAColumn() {
        KpiMondayProperties.Board board = new KpiMondayProperties.Board();
        board.setId("9900001111");
        board.setTicketType(TicketType.INSTALLATION);
        board.setServiceLineColumn("robot_type");
        board.getColumns().setInstallDate("timeline");

        CaseTicket cleaning = mapper.newTicket(
                item("1", null, cells("robot_type", "Cleaning Robot", "timeline", "2026-08-01 - 2026-08-05")), board, NOW);
        CaseTicket delivery = mapper.newTicket(
                item("2", null, cells("robot_type", "Delivery", "timeline", "2026-08-01")), board, NOW);

        assertThat(cleaning.getServiceLine()).isEqualTo(ServiceLine.CLEANING);
        assertThat(cleaning.getTicketType()).isEqualTo(TicketType.INSTALLATION);
        assertThat(cleaning.getInstallDate()).isEqualTo(LocalDate.of(2026, 8, 5));
        assertThat(delivery.getServiceLine()).isEqualTo(ServiceLine.DELIVERY);
    }

    @Test
    void categoryIsMappedAndBlankIsMatchedOnlyWhenListed() {
        KpiMondayProperties.Board board = cleaningBoard();
        MondayItem item = item("1", null, cells("color_mkyj4ncq", "Service case"));
        assertThat(mapper.newTicket(item, board, NOW).getCategory()).isEqualTo("Service case");

        board.setIncludeCategories(List.of("Incident case", "service CASE"));
        assertThat(board.countsCategory("Service case")).isTrue();       // case-insensitive
        assertThat(board.countsCategory("ส่งอะไหล่")).isFalse();
        assertThat(board.countsCategory(null)).isFalse();                // blank not listed
        board.setIncludeCategories(List.of("Incident case", "(blank)"));
        assertThat(board.countsCategory(null)).isTrue();
        assertThat(board.countsCategory("  ")).isTrue();
        board.setIncludeCategories(List.of());
        assertThat(board.countsCategory("anything")).isTrue();           // no list = everything counts
    }
}
