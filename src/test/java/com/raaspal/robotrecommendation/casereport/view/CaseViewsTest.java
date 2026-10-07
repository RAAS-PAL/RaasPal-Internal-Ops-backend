package com.raaspal.robotrecommendation.casereport.view;

import com.raaspal.robotrecommendation.casereport.dto.CaseReportRow;
import com.raaspal.robotrecommendation.casereport.service.SlaStatus;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The tabs' lists, as the console's compose builds them. Made-up customers, branches and
 * tickets; "PCS", "IFS" and "Makro" are the words the rules look for.
 */
class CaseViewsTest {

    private static CaseReportRow row(String id, String project, String branch, LocalDate opened) {
        return CaseReportRow.of(1, project, branch, "Robot", "SN-" + id, "Problem", null, opened, null, 3,
                SlaStatus.WITHIN, null, id);
    }

    private static final LocalDate OCT_2 = LocalDate.of(2026, 10, 2);
    private static final LocalDate SEP_20 = LocalDate.of(2026, 9, 20);

    private static List<String> ids(List<CaseViews.Part> parts) {
        return parts.stream().flatMap(p -> p.rows().stream()).map(CaseReportRow::sourceItemId).toList();
    }

    private final Map<String, List<CaseReportRow>> sheets = Map.of(
            "mk", List.of(row("mk1", "Acme", "A1", OCT_2), row("mk2", "Acme", "A2", OCT_2)),
            "delivery", List.of(row("d1", "Delta Foods", "D1", SEP_20), row("d2", "PCS : Delta", "D2", OCT_2)),
            "cleaning", List.of(row("c1", "Cleanco", "C1", OCT_2), row("c2", "IFS Building", "C2", OCT_2),
                    row("c3", "Cleanco", "C3", OCT_2).withRemoved(true)),
            "makro", List.of(row("m1", "Makro", "M1", OCT_2)),
            "on-hold", List.of(
                    row("mk2", "Acme", "A2", OCT_2).withBoard("DELIVERY"),     // MK's own held case
                    row("h1", "Delta Foods", "D9", OCT_2).withBoard("DELIVERY"),
                    row("h2", "Cleanco", "C9", OCT_2).withBoard("CLEANING"),
                    row("h3", "Makro", "M9", OCT_2).withBoard("CLEANING"),
                    row("h4", "Old row", "X", OCT_2)));                         // no board: cannot be placed

    @Test
    void internalCountsEachCaseOnceAndLeavesRemovedRowsOff() {
        List<CaseViews.Part> parts = CaseViews.compose(CaseViews.View.INTERNAL, sheets, null);
        assertThat(ids(parts)).containsExactly("mk1", "mk2", "d1", "d2", "h1", "c1", "c2", "h2", "m1", "h3");
    }

    @Test
    void makroPcsAndIfsFollowTheRobotFirstAndTheWordsSecond() {
        assertThat(ids(CaseViews.compose(CaseViews.View.MAKRO, sheets, null))).containsExactly("m1", "h3");
        // d2 says PCS; c1's robot is PCS's on the robot list; c2's robot is on it as someone else's.
        CaseViews.RobotLookup robots = (r, sheet) -> switch (r.sourceItemId()) {
            case "c1" -> "PCS";
            case "c2" -> "MK";
            default -> null;
        };
        assertThat(ids(CaseViews.compose(CaseViews.View.PCS, sheets, robots))).containsExactly("m1", "h3", "c1", "d2");
        assertThat(ids(CaseViews.compose(CaseViews.View.IFS, sheets, null))).containsExactly("c2");
        assertThat(ids(CaseViews.compose(CaseViews.View.IFS, sheets, robots))).isEmpty();
    }

    /**
     * MK and Makro span both boards as every tab does: their own sheet, then any other case
     * that is theirs, by the robot first - whether they have cases on the other board or not.
     */
    @Test
    void mkAndMakroTakeTheirCasesFromBothBoards() {
        CaseViews.RobotLookup robots = (r, sheet) -> switch (r.sourceItemId()) {
            case "c2" -> "MK";
            case "d1" -> "Makro";
            default -> null;
        };
        List<CaseViews.Part> mk = CaseViews.compose(CaseViews.View.MK, sheets, robots);
        assertThat(ids(mk)).containsExactly("mk1", "mk2", "c2");
        assertThat(mk).extracting(CaseViews.Part::board).contains("DELIVERY", "CLEANING");
        // No robot list: MK's sheet alone, and the other board's parts are there, empty.
        List<CaseViews.Part> mkByWords = CaseViews.compose(CaseViews.View.MK, sheets, null);
        assertThat(ids(mkByWords)).containsExactly("mk1", "mk2");
        assertThat(mkByWords).filteredOn(p -> "CLEANING".equals(p.board())).isNotEmpty()
                .allMatch(p -> p.rows().isEmpty());
        assertThat(ids(CaseViews.compose(CaseViews.View.MAKRO, sheets, robots))).containsExactly("m1", "h3", "d1");
    }

    @Test
    void onHoldIsTheHeldCasesByBoard() {
        List<CaseViews.Part> parts = CaseViews.compose(CaseViews.View.ON_HOLD, sheets, null);
        assertThat(parts).extracting(CaseViews.Part::board).containsExactly("DELIVERY", "CLEANING");
        assertThat(ids(parts)).containsExactly("mk2", "h1", "h2", "h3");
    }

    @Test
    void entriesAreOneBoardsCasesOpenedInThePeriodOldestFirstAndNumbered() {
        List<CaseViews.Part> parts = CaseViews.compose(CaseViews.View.INTERNAL, sheets, null);

        List<CaseViews.Entry> october = CaseViews.entries(parts, "DELIVERY",
                LocalDate.of(2026, 10, 1), LocalDate.of(2026, 10, 31));
        assertThat(october).extracting(e -> e.row().sourceItemId()).containsExactly("mk1", "mk2", "d2", "h1");

        List<CaseViews.Entry> allTime = CaseViews.entries(parts, "BOTH", null, null);
        assertThat(allTime.get(0).row().sourceItemId()).isEqualTo("d1");    // opened in September
        List<CaseReportRow> rows = CaseViews.rows(allTime);
        assertThat(rows).extracting(CaseReportRow::no).startsWith(1, 2, 3);
        assertThat(rows.get(0).board()).isEqualTo("DELIVERY");
    }
}
