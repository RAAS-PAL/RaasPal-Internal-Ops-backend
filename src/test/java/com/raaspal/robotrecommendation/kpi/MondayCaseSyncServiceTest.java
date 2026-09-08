package com.raaspal.robotrecommendation.kpi;

import com.raaspal.robotrecommendation.casereport.adapters.monday.MondayApiClient;
import com.raaspal.robotrecommendation.casereport.adapters.monday.MondayApiException;
import com.raaspal.robotrecommendation.casereport.adapters.monday.MondayBoardReader;
import com.raaspal.robotrecommendation.casereport.adapters.monday.dto.MondayBoardSchema;
import com.raaspal.robotrecommendation.casereport.adapters.monday.dto.MondayColumnRef;
import com.raaspal.robotrecommendation.casereport.adapters.monday.dto.MondayColumnValue;
import com.raaspal.robotrecommendation.casereport.adapters.monday.dto.MondayGroup;
import com.raaspal.robotrecommendation.casereport.adapters.monday.dto.MondayGroupRead;
import com.raaspal.robotrecommendation.casereport.adapters.monday.dto.MondayItem;
import com.raaspal.robotrecommendation.common.exception.BadRequestException;
import com.raaspal.robotrecommendation.kpi.entity.CaseTicket;
import com.raaspal.robotrecommendation.kpi.entity.CaseTicketSyncRun;
import com.raaspal.robotrecommendation.kpi.entity.ServiceLine;
import com.raaspal.robotrecommendation.kpi.entity.TicketType;
import com.raaspal.robotrecommendation.kpi.repository.CaseTicketRepository;
import com.raaspal.robotrecommendation.kpi.repository.CaseTicketSyncRunRepository;
import com.raaspal.robotrecommendation.kpi.service.MondayCaseSyncService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;

/**
 * A full sync of both configured boards against a mocked monday: rows land,
 * a re-sync is idempotent, a vanished row is marked absent rather than
 * deleted, and one board failing does not stop the other.
 *
 * <p>Deliberately not {@code @Transactional}: the sync writes run rows outside
 * any test transaction, exactly as the scheduler will, so what this asserts is
 * what the console's history endpoint would show.
 */
@SpringBootTest
@TestPropertySource(locations = "classpath:kpi-test.properties")
class MondayCaseSyncServiceTest {

    private static final String CLEANING_BOARD = "3451717331";
    private static final String DELIVERY_BOARD = "1647612496";
    private static final String INSTALL_BOARD = "9900001111";
    private static final OffsetDateTime T1 = OffsetDateTime.parse("2026-09-01T10:00:00+07:00");
    private static final OffsetDateTime T2 = OffsetDateTime.parse("2026-09-07T10:00:00+07:00");

    @Autowired private MondayCaseSyncService service;
    @Autowired private CaseTicketRepository ticketRepository;
    @Autowired private CaseTicketSyncRunRepository runRepository;

    @MockitoBean private MondayApiClient apiClient;
    @MockitoBean private MondayBoardReader boardReader;

    @BeforeEach
    void setUp() {
        runRepository.deleteAll();
        ticketRepository.deleteAll();
        when(apiClient.isConfigured()).thenReturn(true);
        // Group ids are not configured for either board, so the sync discovers them.
        when(boardReader.describeBoard(CLEANING_BOARD)).thenReturn(schema(CLEANING_BOARD, "Cleaning Tickets", "g1"));
        when(boardReader.describeBoard(DELIVERY_BOARD)).thenReturn(schema(DELIVERY_BOARD, "Delivery Tickets", "g2"));
        when(boardReader.describeBoard(INSTALL_BOARD)).thenReturn(schema(INSTALL_BOARD, "Installation Tickets", "g3"));
        installReturns();
    }

    private static MondayBoardSchema schema(String id, String name, String groupId) {
        return new MondayBoardSchema(id, name, 0, List.of(), List.of(new MondayGroup(groupId, "All Case")));
    }

    private static MondayItem cleaningItem(String id, OffsetDateTime updatedAt, String status, String open) {
        return new MondayItem(id, "Ticket " + id, updatedAt, new MondayGroup("g1", "All Case"), List.of(
                new MondayColumnValue("status", "status", status, new MondayColumnRef("status", "Status")),
                new MondayColumnValue("date8", "date", open, new MondayColumnRef("date8", "Open Date")),
                new MondayColumnValue("text0", "text", "GS-" + id, new MondayColumnRef("text0", "Serial"))),
                List.of());
    }

    private static MondayItem deliveryItem(String id, OffsetDateTime updatedAt) {
        return new MondayItem(id, "Ticket " + id, updatedAt, new MondayGroup("g2", "All Case"), List.of(
                new MondayColumnValue("status", "status", "Working on it", new MondayColumnRef("status", "Status")),
                new MondayColumnValue("date5", "date", "2026-08-30", new MondayColumnRef("date5", "Date"))),
                List.of());
    }

    private void cleaningReturns(boolean complete, MondayItem... items) {
        when(boardReader.readGroup(eq(CLEANING_BOARD), eq("g1"), anyList(), eq(false)))
                .thenReturn(new MondayGroupRead(List.of(items), complete));
    }

    /**
     * An installation row. This board carries both robot types and names the
     * column that says which, which is exactly the case that used to break the
     * run row: it has no single service line to record.
     */
    private static MondayItem installItem(String id, String robotType, String timeline) {
        return new MondayItem(id, "Install " + id, T1, new MondayGroup("g3", "All"), List.of(
                new MondayColumnValue("robot_type", "status", robotType, new MondayColumnRef("robot_type", "Type of Robot")),
                new MondayColumnValue("timeline", "timeline", timeline, new MondayColumnRef("timeline", "TimeLine")),
                new MondayColumnValue("text0", "text", "GS-" + id, new MondayColumnRef("text0", "Serial"))),
                List.of());
    }

    private void installReturns(MondayItem... items) {
        when(boardReader.readGroup(eq(INSTALL_BOARD), eq("g3"), anyList(), eq(false)))
                .thenReturn(new MondayGroupRead(List.of(items), true));
    }

    private void deliveryReturns(MondayItem... items) {
        when(boardReader.readGroup(eq(DELIVERY_BOARD), eq("g2"), anyList(), eq(false)))
                .thenReturn(new MondayGroupRead(List.of(items), true));
    }

    private CaseTicket find(String itemId) {
        return ticketRepository.findAll().stream()
                .filter(t -> t.getSourceItemId().equals(itemId))
                .findFirst()
                .orElseThrow();
    }

    @Test
    void firstSyncInsertsEveryRowAndRecordsARunPerBoard() {
        cleaningReturns(true, cleaningItem("X", T1, "Working on it", "2026-08-20"), cleaningItem("Y", T1, "Done", "2026-08-21"));
        deliveryReturns(deliveryItem("Z", T1));
        installReturns(installItem("I", "Cleaning Robot", "2026-08-01 - 2026-08-05"));

        MondayCaseSyncService.SyncSummary summary = service.syncAll(CaseTicketSyncRun.Trigger.MANUAL);

        assertThat(summary.allSucceeded()).isTrue();
        assertThat(summary.boards()).hasSize(3);
        assertThat(summary.boards().get(0).inserted()).isEqualTo(2);
        assertThat(summary.boards().get(1).inserted()).isEqualTo(1);
        assertThat(summary.boards().get(2).inserted()).isEqualTo(1);
        assertThat(ticketRepository.count()).isEqualTo(4);

        CaseTicket x = find("X");
        assertThat(x.getServiceLine()).isEqualTo(ServiceLine.CLEANING);
        assertThat(x.getOpenDate()).isEqualTo(LocalDate.of(2026, 8, 20));
        assertThat(x.getSerialsNormalised()).isEqualTo("GS-X");
        assertThat(x.getTicketType()).isEqualTo(TicketType.CM);
        assertThat(x.isClosed()).isFalse();
        assertThat(find("Y").isClosed()).isTrue();   // "Done" is a closed status on the cleaning board
        assertThat(find("Z").getServiceLine()).isEqualTo(ServiceLine.DELIVERY);

        // The mixed-line board: line comes from the column, install date from the
        // LATER end of the TimeLine, and the run row records no service line at all.
        CaseTicket install = find("I");
        assertThat(install.getTicketType()).isEqualTo(TicketType.INSTALLATION);
        assertThat(install.getServiceLine()).isEqualTo(ServiceLine.CLEANING);
        assertThat(install.getInstallDate()).isEqualTo(LocalDate.of(2026, 8, 5));

        List<CaseTicketSyncRun> runs = runRepository.findAll();
        assertThat(runs).hasSize(3);
        CaseTicketSyncRun installRun = runs.stream()
                .filter(r -> r.getSourceBoardId().equals(INSTALL_BOARD)).findFirst().orElseThrow();
        assertThat(installRun.getServiceLine()).isNull();
        assertThat(installRun.getTicketType()).isEqualTo(TicketType.INSTALLATION);
        assertThat(runs).allMatch(run -> run.getStatus() == CaseTicketSyncRun.Status.SUCCEEDED);
        assertThat(runs).allMatch(run -> run.getTriggeredBy() == CaseTicketSyncRun.Trigger.MANUAL);
        assertThat(runs).allMatch(run -> run.getGroupsRead() == 1 && run.getFinishedAt() != null);
        assertThat(service.status().running()).isFalse();
        assertThat(service.status().lastSummary()).isEqualTo(summary);
    }

    @Test
    void secondSyncUpdatesChangedRowsAndMarksVanishedOnesAbsent() {
        cleaningReturns(true, cleaningItem("X", T1, "Working on it", "2026-08-20"), cleaningItem("Y", T1, "Working on it", "2026-08-21"));
        deliveryReturns(deliveryItem("Z", T1));
        service.syncAll(CaseTicketSyncRun.Trigger.SCHEDULED);

        // X untouched, Y closed since, Z no longer on the board.
        cleaningReturns(true, cleaningItem("X", T1, "Working on it", "2026-08-20"), cleaningItem("Y", T2, "Done", "2026-08-21"));
        deliveryReturns();
        MondayCaseSyncService.SyncSummary summary = service.syncAll(CaseTicketSyncRun.Trigger.SCHEDULED);

        MondayCaseSyncService.BoardResult cleaning = summary.boards().get(0);
        assertThat(cleaning.inserted()).isZero();
        assertThat(cleaning.updated()).isEqualTo(1);
        assertThat(cleaning.unchanged()).isEqualTo(1);
        assertThat(summary.boards().get(1).markedAbsent()).isEqualTo(1);

        assertThat(ticketRepository.count()).isEqualTo(3);           // never deleted
        assertThat(ticketRepository.countByPresentTrue()).isEqualTo(2);
        assertThat(find("Y").isClosed()).isTrue();
        assertThat(find("Z").isPresent()).isFalse();
        assertThat(runRepository.count()).isEqualTo(6);              // 3 boards x 2 syncs
    }

    /** A read cut short by the page guard must not "delete" the tail of a big group. */
    @Test
    void incompleteReadDoesNotMarkAnythingAbsent() {
        cleaningReturns(true, cleaningItem("X", T1, "Working on it", "2026-08-20"));
        deliveryReturns();
        service.syncAll(CaseTicketSyncRun.Trigger.MANUAL);

        cleaningReturns(false);
        MondayCaseSyncService.SyncSummary summary = service.syncAll(CaseTicketSyncRun.Trigger.MANUAL);

        assertThat(summary.boards().get(0).markedAbsent()).isZero();
        assertThat(find("X").isPresent()).isTrue();
    }

    @Test
    void aFailingBoardIsRecordedAndDoesNotStopTheOther() {
        when(boardReader.readGroup(eq(CLEANING_BOARD), eq("g1"), anyList(), eq(false)))
                .thenThrow(new MondayApiException("monday board 3451717331 was not found, or is not visible to the configured token"));
        deliveryReturns(deliveryItem("Z", T1));

        MondayCaseSyncService.SyncSummary summary = service.syncAll(CaseTicketSyncRun.Trigger.SCHEDULED);

        assertThat(summary.allSucceeded()).isFalse();
        assertThat(summary.boards().get(0).status()).isEqualTo(CaseTicketSyncRun.Status.FAILED);
        assertThat(summary.boards().get(0).error()).contains("not visible");
        assertThat(summary.boards().get(1).status()).isEqualTo(CaseTicketSyncRun.Status.SUCCEEDED);
        assertThat(summary.boards().get(2).status()).isEqualTo(CaseTicketSyncRun.Status.SUCCEEDED);
        assertThat(ticketRepository.count()).isEqualTo(1);

        CaseTicketSyncRun failed = runRepository.findAll().stream()
                .filter(run -> run.getSourceBoardId().equals(CLEANING_BOARD))
                .findFirst().orElseThrow();
        assertThat(failed.getStatus()).isEqualTo(CaseTicketSyncRun.Status.FAILED);
        assertThat(failed.getErrorMessage()).contains("not visible");
        assertThat(failed.getFinishedAt()).isNotNull();
        assertThat(service.status().running()).isFalse();
    }

    @Test
    void refusesToRunWithoutAToken() {
        when(apiClient.isConfigured()).thenReturn(false);

        assertThatThrownBy(() -> service.syncAll(CaseTicketSyncRun.Trigger.MANUAL))
                .isInstanceOf(BadRequestException.class)
                .hasMessageContaining("MONDAY_API_TOKEN");
        assertThat(service.status().configured()).isFalse();
        assertThat(runRepository.count()).isZero();
    }
}
