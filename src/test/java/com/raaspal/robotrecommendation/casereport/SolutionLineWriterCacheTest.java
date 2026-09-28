package com.raaspal.robotrecommendation.casereport;

import com.raaspal.robotrecommendation.ai.service.CaseSolutionAiService;
import com.raaspal.robotrecommendation.casereport.adapters.monday.dto.MondayItem;
import com.raaspal.robotrecommendation.casereport.adapters.monday.dto.MondayUpdate;
import com.raaspal.robotrecommendation.casereport.service.SolutionLineWriter;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * The sheets regenerate every few minutes, so the model must be asked again only when
 * a ticket has something new to say. These pin what counts as new.
 */
class SolutionLineWriterCacheTest {

    private static final LocalDate TODAY = LocalDate.of(2026, 9, 28);

    private final CaseSolutionAiService ai = mock(CaseSolutionAiService.class);
    private final SolutionLineWriter writer = new SolutionLineWriter(ai);

    @Test
    void anUnchangedTicketIsNotAskedAgain() {
        when(ai.summariseProgress(any())).thenReturn("27-Sep เปลี่ยนแบตเตอรี่");
        MondayItem ticket = ticket(comment("u1", "เปลี่ยนแบตเตอรี่แล้ว", "2026-09-27T03:00:00Z"));

        String first = write(ticket, "Pending", TODAY);
        String second = write(ticket, "Pending", TODAY);

        assertThat(second).isEqualTo(first);
        verify(ai, times(1)).summariseProgress(any());
    }

    @Test
    void aNewCommentAStatusChangeOrANewDayIsAskedAgain() {
        when(ai.summariseProgress(any())).thenReturn("27-Sep เปลี่ยนแบตเตอรี่");
        MondayUpdate first = comment("u1", "เปลี่ยนแบตเตอรี่แล้ว", "2026-09-27T03:00:00Z");

        write(ticket(first), "Pending", TODAY);
        write(ticket(comment("u2", "ทดสอบแล้วใช้งานได้", "2026-09-28T02:00:00Z"), first), "Pending", TODAY);
        write(ticket(first), "Check", TODAY);
        write(ticket(first), "Pending", TODAY.plusDays(1));

        verify(ai, times(4)).summariseProgress(any());
    }

    @Test
    void aBlankAnswerIsNotKept() {
        when(ai.summariseProgress(any())).thenReturn("", "27-Sep เปลี่ยนแบตเตอรี่");
        MondayItem ticket = ticket(comment("u1", "เปลี่ยนแบตเตอรี่แล้ว", "2026-09-27T03:00:00Z"));

        write(ticket, "Pending", TODAY);
        assertThat(write(ticket, "Pending", TODAY)).contains("เปลี่ยนแบตเตอรี่");
        verify(ai, times(2)).summariseProgress(any());
    }

    private String write(MondayItem item, String status, LocalDate asOf) {
        return writer.write(item, null, "M154 โลตัส จันทบุรี", "แบตเสื่อม", status, null,
                LocalDate.of(2026, 9, 26), asOf);
    }

    /** Comments newest first, as monday returns them. */
    private static MondayItem ticket(MondayUpdate... newestFirst) {
        return new MondayItem("1", "M154", null, null, List.of(), List.of(newestFirst), null);
    }

    private static MondayUpdate comment(String id, String body, String at) {
        return new MondayUpdate(id, body, OffsetDateTime.parse(at), null, null);
    }
}
