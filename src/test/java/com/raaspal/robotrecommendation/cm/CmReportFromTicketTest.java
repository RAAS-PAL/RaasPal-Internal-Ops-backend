package com.raaspal.robotrecommendation.cm;

import com.raaspal.robotrecommendation.ai.dto.CmReportDraft;
import com.raaspal.robotrecommendation.ai.service.CmReportExtractionService;
import com.raaspal.robotrecommendation.casereport.entity.CaseTicket;
import com.raaspal.robotrecommendation.casereport.entity.CaseTicketUpdate;
import com.raaspal.robotrecommendation.casereport.repository.CaseTicketRepository;
import com.raaspal.robotrecommendation.casereport.repository.CaseTicketUpdateRepository;
import com.raaspal.robotrecommendation.casereport.service.CaseTicketSyncService;
import com.raaspal.robotrecommendation.cm.dto.CmTicketDraft;
import com.raaspal.robotrecommendation.cm.dto.CmTicketSummary;
import com.raaspal.robotrecommendation.cm.repository.CmReportRepository;
import com.raaspal.robotrecommendation.cm.service.CmReportService;
import com.raaspal.robotrecommendation.user.repository.UserRepository;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyCollection;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/** A CM report drafted from a synced monday ticket: what the model sees, and what it may not decide. */
class CmReportFromTicketTest {

    private final CmReportRepository reports = mock(CmReportRepository.class);
    private final CmReportExtractionService extractor = mock(CmReportExtractionService.class);
    private final CaseTicketRepository tickets = mock(CaseTicketRepository.class);
    private final CaseTicketUpdateRepository updates = mock(CaseTicketUpdateRepository.class);
    private final CmReportService service = new CmReportService(reports, extractor, mock(UserRepository.class), tickets, updates);

    private final CaseTicket ticket = CaseTicket.builder()
            .id(UUID.randomUUID()).sourceBoardId(CaseTicketSyncService.DELIVERY_BOARD).sourceItemId("12152391009")
            .itemName("MK สาขาเซ็นทรัลเวสต์เกต หุ่นไม่วิ่ง").projectRaw("MK Restaurant").branchRaw("Central Westgate")
            .robotModel("BellaBot").serialNumbers("PUDU-001").status("Done").mainIssue("หุ่นยนต์ไม่วิ่ง")
            .openDate(LocalDate.parse("2026-09-10")).isPresent(true).build();

    private final CaseTicketUpdate first = CaseTicketUpdate.builder().id(UUID.randomUUID()).caseTicketId(ticket.getId())
            .sourceUpdateId("1").body("ลูกค้าแจ้งหุ่นไม่วิ่ง").creatorName("Boss")
            .postedAt(LocalDateTime.parse("2026-09-10T03:00:00")).build();
    private final CaseTicketUpdate fix = CaseTicketUpdate.builder().id(UUID.randomUUID()).caseTicketId(ticket.getId())
            .sourceUpdateId("2").body("เปลี่ยนล้อซ้าย ทดสอบวิ่งปกติ").creatorName("Nut")
            .postedAt(LocalDateTime.parse("2026-09-11T20:30:00")).build();   // 03:30 on the 12th in Bangkok

    @Test
    void theSourceTextCarriesColumnsThenTheThreadInOrder() {
        String text = CmReportService.sourceTextOf(ticket, List.of(first, fix));

        assertThat(text).startsWith("Ticket No. : 12152391009\nBoard : Delivery Tickets");
        assertThat(text).contains("Serial number : PUDU-001").contains("อาการ / Main issue : หุ่นยนต์ไม่วิ่ง");
        assertThat(text).contains("--- Comments (2, oldest first) ---");
        assertThat(text.indexOf("[2026-09-10 10:00 Boss]")).isLessThan(text.indexOf("[2026-09-12 03:30 Nut]"));
        assertThat(text).doesNotContain("Province");   // blank columns are left out, not printed empty
    }

    @Test
    void theTicketsFactsOverruleTheModel() {
        when(tickets.findById(ticket.getId())).thenReturn(Optional.of(ticket));
        when(updates.findByCaseTicketIdInOrderByPostedAtAsc(any())).thenReturn(List.of(first, fix));
        when(reports.findExistingTicketNos(anyCollection())).thenReturn(List.of());
        when(extractor.extractCmReport(any())).thenReturn(new CmReportDraft(
                null, "999", "MK", "Somchai", "Bella", "WRONG", "wheel", "inspected", List.of("replaced wheel"), "ok"));

        CmTicketDraft d = service.parseTicket(ticket.getId());

        assertThat(d.draft().ticketNo()).isEqualTo("12152391009");
        assertThat(d.draft().serialNumber()).isEqualTo("PUDU-001");
        assertThat(d.draft().robotModel()).isEqualTo("BellaBot");
        assertThat(d.draft().technicianName()).isEqualTo("Nut");          // latest comment's author
        assertThat(d.draft().reportDate()).isEqualTo("2026-09-12");       // latest comment's Bangkok date
        assertThat(d.draft().customerName()).isEqualTo("MK");             // the model's, when it found one
        assertThat(d.draft().correctiveActions()).containsExactly("replaced wheel");
        assertThat(d.ticket().commentCount()).isEqualTo(2);
        assertThat(d.ticket().hasReport()).isFalse();
        assertThat(d.sourceText()).contains("เปลี่ยนล้อซ้าย");
    }

    @Test
    void theListMarksTicketsAlreadyReportedAndFilters() {
        CaseTicket other = CaseTicket.builder().id(UUID.randomUUID()).sourceBoardId(CaseTicketSyncService.CLEANING_BOARD)
                .sourceItemId("555").itemName("Big C Rama 2 แปรงไม่หมุน").serialNumbers("GS-77")
                .openDate(LocalDate.parse("2026-09-15")).isPresent(true).build();
        when(tickets.findBySourceBoardIdAndIsPresentTrue(CaseTicketSyncService.CLEANING_BOARD)).thenReturn(List.of(other));
        when(tickets.findBySourceBoardIdAndIsPresentTrue(CaseTicketSyncService.DELIVERY_BOARD)).thenReturn(List.of(ticket));
        when(updates.findByCaseTicketIdInOrderByPostedAtAsc(any())).thenReturn(List.of(first, fix));
        when(reports.findExistingTicketNos(anyCollection())).thenReturn(List.of("555"));

        List<CmTicketSummary> all = service.tickets(null, null);
        assertThat(all).extracting(CmTicketSummary::caseId).containsExactly("555", "12152391009");   // newest first
        assertThat(all.get(0).hasReport()).isTrue();
        assertThat(all.get(1).commentCount()).isEqualTo(2);

        assertThat(service.tickets(CmTicketSummary.CmTicketBoard.DELIVERY, "westgate"))
                .extracting(CmTicketSummary::caseId).containsExactly("12152391009");
        assertThat(service.tickets(null, "GS-77")).extracting(CmTicketSummary::board)
                .containsExactly(CmTicketSummary.CmTicketBoard.CLEANING);
    }
}
