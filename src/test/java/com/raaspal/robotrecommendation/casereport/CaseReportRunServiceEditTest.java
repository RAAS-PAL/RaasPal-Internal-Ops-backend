package com.raaspal.robotrecommendation.casereport;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import com.raaspal.robotrecommendation.casereport.dto.CaseReportRow;
import com.raaspal.robotrecommendation.casereport.dto.CaseRowEdit;
import com.raaspal.robotrecommendation.casereport.entity.CaseReportDefinition;
import com.raaspal.robotrecommendation.casereport.entity.CaseReportRun;
import com.raaspal.robotrecommendation.casereport.entity.CaseRunStatus;
import com.raaspal.robotrecommendation.casereport.repository.CaseReportDefinitionRepository;
import com.raaspal.robotrecommendation.casereport.repository.CaseReportRunRepository;
import com.raaspal.robotrecommendation.casereport.service.CaseReportRunService;
import com.raaspal.robotrecommendation.casereport.service.CleaningPendingReportGenerator;
import com.raaspal.robotrecommendation.casereport.service.MkPendingReportGenerator;
import com.raaspal.robotrecommendation.casereport.service.SlaCalculator;
import com.raaspal.robotrecommendation.casereport.service.SlaStatus;
import com.raaspal.robotrecommendation.common.exception.BadRequestException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.time.ZoneId;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Correcting a generated row, and what a regeneration does with the correction.
 *
 * <p>Plain Mockito, no Spring context: the service takes its collaborators through the
 * constructor and the rows live in a JSON column, so a stubbed repository is enough.
 */
class CaseReportRunServiceEditTest {

    private static final LocalDate TODAY = LocalDate.now(ZoneId.of("Asia/Bangkok"));
    private static final UUID DEFINITION_ID = UUID.randomUUID();

    private final CaseReportRunRepository runs = mock(CaseReportRunRepository.class);
    private final MkPendingReportGenerator generator = mock(MkPendingReportGenerator.class);
    private final ObjectMapper json = new ObjectMapper().registerModule(new JavaTimeModule());

    private CaseReportRunService service;
    private CaseReportRun run;

    @BeforeEach
    void storedDraft() {
        CaseReportDefinition definition = CaseReportDefinition.builder()
                .id(DEFINITION_ID)
                .code(CaseReportDefinition.MK_PENDING)
                .build();
        CaseReportDefinitionRepository definitions = mock(CaseReportDefinitionRepository.class);
        when(definitions.findByCode(CaseReportDefinition.MK_PENDING))
                .thenReturn(Optional.of(definition));

        run = CaseReportRun.builder()
                .definitionId(DEFINITION_ID)
                .runDate(TODAY)
                .status(CaseRunStatus.AWAITING_APPROVAL)
                .build();
        run.setRowsJson(write(List.of(
                row(1, "1001", "โลตัส จันทบุรี", TODAY.minusDays(6), SlaStatus.BREACHED),
                row(2, "1002", "บิ๊กซี-กัลปพฤกษ์", TODAY.minusDays(2), SlaStatus.WITHIN))));
        when(runs.findByDefinitionIdAndRunDate(DEFINITION_ID, TODAY)).thenReturn(Optional.of(run));
        when(runs.save(any())).thenAnswer(inv -> inv.getArgument(0));

        service = new CaseReportRunService(definitions, runs, generator,
                mock(CleaningPendingReportGenerator.class), new SlaCalculator(List.of("Bangkok")),
                json);
    }

    /** The case that prompted this: the team counts from a later date than the ticket's. */
    @Test
    void correctingTheOpenDateRecomputesDaysAndSlaUnlessTyped() {
        CaseReportRow saved = service.editRow(CaseReportDefinition.MK_PENDING, TODAY, "1001",
                edit("M154 โลตัส จันทบุรี", TODAY.minusDays(2), null, null));

        assertThat(saved.branch()).isEqualTo("M154 โลตัส จันทบุรี");
        assertThat(saved.days()).isEqualTo(2);
        assertThat(saved.sla()).isEqualTo(SlaStatus.WITHIN);
        assertThat(saved.slaLabel()).isEqualTo("Within SLA");
        assertThat(saved.edited()).isTrue();
        assertThat(saved.no()).isEqualTo(1);
        assertThat(saved.sourceItemId()).isEqualTo("1001");

        // Stored, not only returned.
        assertThat(rows()).extracting(CaseReportRow::branch)
                .containsExactly("M154 โลตัส จันทบุรี", "บิ๊กซี-กัลปพฤกษ์");
    }

    @Test
    void typedDaysAndSlaWinOverTheArithmetic() {
        CaseReportRow saved = service.editRow(CaseReportDefinition.MK_PENDING, TODAY, "1001",
                edit("โลตัส จันทบุรี", TODAY.minusDays(6), 3, SlaStatus.ON_HOLD));

        assertThat(saved.days()).isEqualTo(3);
        assertThat(saved.sla()).isEqualTo(SlaStatus.ON_HOLD);
        assertThat(saved.slaLabel()).isEqualTo("On Hold");
    }

    @Test
    void blankCellsAreStoredAsNothingNotAsEmptyStrings() {
        CaseReportRow saved = service.editRow(CaseReportDefinition.MK_PENDING, TODAY, "1002",
                new CaseRowEdit("MK", "  ", null, "", "ฝาครอบหลุด", null, null, null, null, null,
                        "Bangkok"));

        assertThat(saved.branch()).isNull();
        assertThat(saved.serialNumber()).isNull();
        assertThat(saved.days()).isNull();
        assertThat(saved.sla()).isEqualTo(SlaStatus.UNKNOWN);
    }

    /**
     * What makes editing safe to offer at all: pressing Regenerate must not undo it.
     * The board is re-read, but the corrected row comes through as it was.
     */
    @Test
    void regeneratingKeepsAnEditedRowAndRebuildsTheRest() {
        service.editRow(CaseReportDefinition.MK_PENDING, TODAY, "1001",
                edit("M154 โลตัส จันทบุรี", TODAY.minusDays(2), null, null));

        // The board now lists the same two tickets in the other order, plus a new one,
        // and its value for 1001 is still the uncorrected one.
        when(generator.generate(TODAY)).thenReturn(List.of(
                row(1, "1002", "บิ๊กซี-กัลปพฤกษ์", TODAY.minusDays(2), SlaStatus.WITHIN),
                row(2, "1001", "โลตัส จันทบุรี", TODAY.minusDays(6), SlaStatus.BREACHED),
                row(3, "1003", "โลตัสเพชรบูรณ์", TODAY, SlaStatus.WITHIN)));

        List<CaseReportRow> regenerated =
                service.rowsFor(CaseReportDefinition.MK_PENDING, TODAY, true);

        assertThat(regenerated).extracting(CaseReportRow::no).containsExactly(1, 2, 3);
        assertThat(regenerated).extracting(CaseReportRow::branch)
                .containsExactly("บิ๊กซี-กัลปพฤกษ์", "M154 โลตัส จันทบุรี", "โลตัสเพชรบูรณ์");
        assertThat(regenerated.get(1).edited()).isTrue();
        assertThat(regenerated.get(1).days()).isEqualTo(2);
        assertThat(regenerated.get(0).edited()).isFalse();
    }

    /**
     * Yesterday's draft can still be corrected but not re-read: the board describes
     * today. The refusal must say that, not claim nothing was generated.
     */
    @Test
    void anEarlierDaysDraftCanBeEditedButNotRegenerated() {
        LocalDate yesterday = TODAY.minusDays(1);
        run.setRunDate(yesterday);
        when(runs.findByDefinitionIdAndRunDate(DEFINITION_ID, yesterday)).thenReturn(Optional.of(run));

        assertThatThrownBy(() -> service.rowsFor(CaseReportDefinition.MK_PENDING, yesterday, true))
                .isInstanceOf(BadRequestException.class)
                .hasMessageContaining("from an earlier day")
                .hasMessageContaining("editing");

        CaseReportRow saved = service.editRow(CaseReportDefinition.MK_PENDING, yesterday, "1001",
                edit("M154 โลตัส จันทบุรี", yesterday.minusDays(2), null, null));
        assertThat(saved.days()).isEqualTo(2);
        assertThat(service.rowsFor(CaseReportDefinition.MK_PENDING, yesterday, false))
                .extracting(CaseReportRow::branch)
                .containsExactly("M154 โลตัส จันทบุรี", "บิ๊กซี-กัลปพฤกษ์");
    }

    /**
     * The M057 case: a ticket the team counts as pending that the board keeps in another
     * group. Added by hand, it gets a verdict from its own province and open date, sits at
     * the bottom, and is still there after the board is re-read.
     */
    @Test
    void aRowAddedByHandGetsAVerdictAndSurvivesRegeneration() {
        CaseReportRow added = service.addRow(CaseReportDefinition.MK_PENDING, TODAY,
                new CaseRowEdit("MK", "M057 ศรีราชานคร", "Pudu 1", "PD1020211015009",
                        "ล้อหุ่นยนต์เสื่อมสภาพ", null, TODAY.minusDays(7), TODAY.plusDays(1),
                        null, null, "ชลบุรี"));

        assertThat(added.no()).isEqualTo(3);
        assertThat(added.sourceItemId()).startsWith(CaseReportRow.MANUAL_PREFIX);
        assertThat(added.isManual()).isTrue();
        assertThat(added.edited()).isTrue();
        assertThat(added.days()).isEqualTo(7);
        assertThat(added.sla()).isEqualTo(SlaStatus.BREACHED); // upcountry, 7 > 5
        assertThat(run.getTicketCount()).isEqualTo(3);

        when(generator.generate(TODAY)).thenReturn(List.of(
                row(1, "1002", "บิ๊กซี-กัลปพฤกษ์", TODAY.minusDays(2), SlaStatus.WITHIN)));

        List<CaseReportRow> regenerated =
                service.rowsFor(CaseReportDefinition.MK_PENDING, TODAY, true);

        assertThat(regenerated).extracting(CaseReportRow::branch)
                .containsExactly("บิ๊กซี-กัลปพฤกษ์", "M057 ศรีราชานคร");
        assertThat(regenerated).extracting(CaseReportRow::no).containsExactly(1, 2);
    }

    @Test
    void onlyARowAddedByHandCanBeRemoved() {
        CaseReportRow added = service.addRow(CaseReportDefinition.MK_PENDING, TODAY,
                edit("M057 ศรีราชานคร", TODAY.minusDays(7), null, null));

        assertThatThrownBy(() -> service.removeRow(CaseReportDefinition.MK_PENDING, TODAY, "1001"))
                .isInstanceOf(BadRequestException.class)
                .hasMessageContaining("comes from the monday board");

        service.removeRow(CaseReportDefinition.MK_PENDING, TODAY, added.sourceItemId());

        assertThat(rows()).extracting(CaseReportRow::sourceItemId).containsExactly("1001", "1002");
        assertThat(rows()).extracting(CaseReportRow::no).containsExactly(1, 2);
        assertThat(run.getTicketCount()).isEqualTo(2);
    }

    /** Editing a board row can fill in a province the ticket lacked, and the verdict follows. */
    @Test
    void fillingInAMissingProvinceGivesTheRowAVerdict() {
        run.setRowsJson(write(List.of(CaseReportRow.of(1, "MK", "โลตัสพิมาย", "Pudu 1", "PD9",
                "ปัญหา", null, TODAY.minusDays(4), null, 4, SlaStatus.UNKNOWN, null, "1009"))));

        CaseReportRow saved = service.editRow(CaseReportDefinition.MK_PENDING, TODAY, "1009",
                new CaseRowEdit("MK", "โลตัสพิมาย", "Pudu 1", "PD9", "ปัญหา", null,
                        TODAY.minusDays(4), null, null, null, "นครราชสีมา"));

        assertThat(saved.province()).isEqualTo("นครราชสีมา");
        assertThat(saved.sla()).isEqualTo(SlaStatus.WITHIN); // upcountry, 4 <= 5
    }

    @Test
    void aSentReportCannotBeEdited() {
        run.setStatus(CaseRunStatus.SENT);

        assertThatThrownBy(() -> service.editRow(CaseReportDefinition.MK_PENDING, TODAY, "1001",
                edit("x", TODAY, null, null)))
                .isInstanceOf(BadRequestException.class)
                .hasMessageContaining("already been sent");
    }

    @Test
    void aTicketNotOnTheReportIsRefused() {
        assertThatThrownBy(() -> service.editRow(CaseReportDefinition.MK_PENDING, TODAY, "9999",
                edit("x", TODAY, null, null)))
                .isInstanceOf(BadRequestException.class)
                .hasMessageContaining("no row for ticket 9999");
    }

    private static CaseReportRow row(int no, String itemId, String branch, LocalDate opened,
                                     SlaStatus sla) {
        return CaseReportRow.of(no, "MK", branch, "Pudu 1", "PD" + itemId, "ปัญหา", null,
                opened, null, SlaCalculator.daysOpen(opened, TODAY), sla, "Bangkok", itemId);
    }

    private static CaseRowEdit edit(String branch, LocalDate opened, Integer days, SlaStatus sla) {
        return new CaseRowEdit("MK", branch, "Pudu 1", "PD1", "ปัญหา", null, opened, null,
                days, sla, "Bangkok");
    }

    private List<CaseReportRow> rows() {
        try {
            return json.readValue(run.getRowsJson(),
                    json.getTypeFactory().constructCollectionType(List.class, CaseReportRow.class));
        } catch (Exception e) {
            throw new AssertionError(e);
        }
    }

    private String write(List<CaseReportRow> rows) {
        try {
            return json.writeValueAsString(rows);
        } catch (Exception e) {
            throw new AssertionError(e);
        }
    }
}
