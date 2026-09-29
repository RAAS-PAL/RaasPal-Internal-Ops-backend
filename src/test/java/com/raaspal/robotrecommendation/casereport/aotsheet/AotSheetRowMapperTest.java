package com.raaspal.robotrecommendation.casereport.aotsheet;

import com.raaspal.robotrecommendation.casereport.adapters.googlesheet.GoogleSheetReader;
import com.raaspal.robotrecommendation.casereport.adapters.googlesheet.dto.SheetTable;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * How AOT sheet rows are read: named fields by header, and the two rules that matter most
 * - which rows count as closed, and which ids would stop a sync. Values are made up.
 */
class AotSheetRowMapperTest {

    private static final List<Object> HEADERS = List.of(
            "n", "Name req", "AOT Ticket no.", "Issue Date", "Location", "Model", "S/N",
            "Problem details", "Request for Spare part", "RE RAAS Verify Issue", "repair By",
            "Status", "Closed Date");

    @Test
    void mapsTheDescriptiveColumnsByHeader() {
        AotSheetCase c = mapper(props()).map(table(
                row(7, "someone", "DMK000001", 45133, "DMK", "M75", "GS-TEST-0001",
                        "robot stopped", "limit switch", "sensor replaced", "AOTGA", "", ""))
                .rows().get(0));

        assertThat(c.sheetRow()).isEqualTo(2);
        assertThat(c.rowId()).isNull();                     // no id column configured
        assertThat(c.ticketNo()).isEqualTo("DMK000001");
        assertThat(c.openDate()).isEqualTo(LocalDate.of(2023, 7, 26));
        assertThat(c.site()).isEqualTo("DMK");
        assertThat(c.model()).isEqualTo("M75");
        assertThat(c.serialNumbers()).isEqualTo("GS-TEST-0001");
        assertThat(c.problem()).isEqualTo("robot stopped");
        assertThat(c.requestedPart()).isEqualTo("limit switch");
        assertThat(c.verifyNote()).isEqualTo("sensor replaced");
        assertThat(c.repairBy()).isEqualTo("AOTGA");
    }

    /** Unknown is not open: with no closure column, nothing is claimed either way. */
    @Test
    void closureIsUnknownUntilAColumnIsConfigured() {
        AotSheetRowMapper mapper = mapper(props());

        assertThat(mapper.knowsClosure()).isFalse();
        assertThat(mapper.map(table(row(1, "", "", 45133)).rows().get(0)).closed()).isNull();
    }

    @Test
    void aClosedStatusClosesTheCaseIgnoringCase() {
        AotSheetProperties props = props();
        props.setStatusHeader("Status");
        props.setClosedStatuses(List.of("Closed", "Done"));
        SheetTable table = table(
                row(1, "", "", 45133, "", "", "", "", "", "", "", "closed"),
                row(2, "", "", 45133, "", "", "", "", "", "", "", "Waiting part"),
                row(3, "", "", 45133, "", "", "", "", "", "", "", ""));

        List<AotSheetCase> cases = mapper(props).mapAll(table).cases();

        assertThat(cases).extracting(AotSheetCase::closed).containsExactly(true, false, false);
    }

    @Test
    void anyCloseDateClosesTheCase() {
        AotSheetProperties props = props();
        props.setCloseDateHeader("Closed Date");
        SheetTable table = table(
                row(1, "", "", 45133, "", "", "", "", "", "", "", "", 45140),
                row(2, "", "", 45133));

        List<AotSheetCase> cases = mapper(props).mapAll(table).cases();

        assertThat(cases.get(0).closed()).isTrue();
        assertThat(cases.get(0).closeDate()).isEqualTo(LocalDate.of(2023, 8, 2));
        assertThat(cases.get(1).closed()).isFalse();
    }

    @Test
    void idsMissingOrRepeatedAreReported() {
        AotSheetProperties props = props();
        props.setRowIdHeader("n");
        SheetTable table = table(
                row(1, "a"), row(2, "b"), row(2, "c"), row("", "d"), row(3.0, "e"));

        AotSheetRowMapper.MappedSheet mapped = mapper(props).mapAll(table);

        assertThat(mapped.withoutId()).isEqualTo(1);
        assertThat(mapped.duplicateIds()).containsExactly("2");
        // 3.0 from the API is id "3", not "3.0".
        assertThat(mapped.identified()).extracting(AotSheetCase::rowId).containsExactly("1", "2", "2", "3");
    }

    /**
     * A renamed id or status column must stop the sync; a renamed descriptive column
     * only blanks its field.
     */
    @Test
    void missingHeadersAreSplitByWhetherTheSyncCanRunWithoutThem() {
        AotSheetProperties props = props();
        props.setRowIdHeader("Case ID");
        props.setStatusHeader("Status");
        props.setModelHeader("Robot model");

        AotSheetRowMapper mapper = mapper(props);

        assertThat(mapper.missingHeaders()).containsExactly("Case ID", "Robot model");
        assertThat(mapper.missingCriticalHeaders()).containsExactly("Case ID");
    }

    @Test
    void anOpenDateThatIsNotADateIsCounted() {
        SheetTable table = table(row(1, "", "", "ข้อมูลอ้างอิง"), row(2, "", "", 45133), row(3));
        AotSheetRowMapper mapper = mapper(props());

        assertThat(table.rows()).filteredOn(mapper::openDateUnreadable).hasSize(1);
    }

    @Test
    void notReadyUntilIdentityAndClosureAreConfigured() {
        AotSheetProperties props = props();
        assertThat(props.notReadyForSync()).hasSize(2);

        props.setRowIdHeader("n");
        props.setStatusHeader("Status");
        assertThat(props.notReadyForSync()).containsExactly(
                "a status column but no values that mean closed");

        props.setClosedStatuses(List.of("Closed"));
        assertThat(props.notReadyForSync()).isEmpty();
    }

    @Test
    void theItemIdIsPrefixedWithTheSpreadsheet() {
        assertThat(AotSheetSyncService.itemId("sheet-abc", "12")).isEqualTo("sheet-abc:12");
    }

    private static AotSheetProperties props() {
        AotSheetProperties props = new AotSheetProperties();
        props.setSpreadsheetId("sheet-abc");
        return props;
    }

    private static AotSheetRowMapper mapper(AotSheetProperties props) {
        return AotSheetRowMapper.forTable(table(), props);
    }

    @SafeVarargs
    private static SheetTable table(List<Object>... rows) {
        List<List<Object>> values = new ArrayList<>();
        values.add(HEADERS);
        values.addAll(Arrays.asList(rows));
        return GoogleSheetReader.parse(values, 1);
    }

    private static List<Object> row(Object... cells) {
        return Arrays.asList(cells);
    }
}
