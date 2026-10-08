package com.raaspal.robotrecommendation.casereport.share;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.raaspal.robotrecommendation.casereport.aotsheet.AotgaTracker;
import com.raaspal.robotrecommendation.casereport.dto.CaseReportRow;
import com.raaspal.robotrecommendation.casereport.service.CaseReportRunService;
import com.raaspal.robotrecommendation.casereport.service.SlaStatus;
import com.raaspal.robotrecommendation.casereport.view.CaseViews;
import com.raaspal.robotrecommendation.common.exception.BadRequestException;
import com.raaspal.robotrecommendation.common.exception.ResourceNotFoundException;
import com.raaspal.robotrecommendation.pm.service.PmPublicService;
import com.raaspal.robotrecommendation.user.repository.UserRepository;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** Public links to pending cases. Made-up customers and cases. */
class CaseShareServiceTest {

    private final CaseShareLinkRepository links = mock(CaseShareLinkRepository.class);
    private final CaseReportRunService runs = mock(CaseReportRunService.class);
    private final AotgaTracker aotga = mock(AotgaTracker.class);
    private final PmPublicService pm = mock(PmPublicService.class);
    private final CaseShareService service = new CaseShareService(links, runs, mock(CaseViews.class), aotga, pm,
            mock(UserRepository.class), new ObjectMapper());

    private static CaseReportRow row(int no, String project) {
        return CaseReportRow.of(no, project, "B01 Somewhere", "Pudu 1", "SN" + no, "Will not start",
                "05/10 replaced the battery", LocalDate.of(2026, 10, 1), null, 6, SlaStatus.WITHIN,
                null, "item-" + no);
    }

    private static AotgaTracker.Case aot(String ticket, LocalDate issued) {
        return new AotgaTracker.Case(ticket, "Site", "Robot", "SN", "Problem", issued, 3, "open", "#ffffff",
                null, AotgaTracker.Stage.REPORTED, null, null, null, null, "internal note", "Someone", 7, false);
    }

    private static CaseReportRunService.Stored stored(LocalDate day, CaseReportRow... rows) {
        return new CaseReportRunService.Stored(day, day.atTime(9, 15), List.of(rows));
    }

    @Test
    void aLinkShowsOnlyItsCustomersLiveRowsNumberedAgain() {
        List<CaseReportRow> sheet = List.of(row(1, "Acme Foods"), row(2, "Blue Mart"),
                row(3, "acme foods ").withRemoved(true), row(4, " ACME FOODS"));

        List<CaseShareService.PublicRow> shown = CaseShareService.rows(sheet, List.of("Acme Foods"));

        assertThat(shown).extracting(CaseShareService.PublicRow::no).containsExactly(1, 2);
        assertThat(shown).extracting(CaseShareService.PublicRow::serialNumber).containsExactly("SN1", "SN4");
        assertThat(CaseShareService.rows(sheet, null)).hasSize(3);
    }

    @Test
    void aotCasesAreThePeriodsNewestFirstAndUndatedOnlyInAllTime() {
        List<AotgaTracker.Case> cases = Arrays.asList(aot("T1", LocalDate.of(2026, 10, 2)),
                aot("T2", null), aot("T3", LocalDate.of(2026, 9, 30)), aot("T4", LocalDate.of(2026, 10, 6)));

        assertThat(CaseShareService.aotCases(cases, new CaseShareService.Period(
                LocalDate.of(2026, 10, 1), LocalDate.of(2026, 10, 31))))
                .extracting(CaseShareService.PublicAotCase::ticketNo).containsExactly("T4", "T1");
        assertThat(CaseShareService.aotCases(cases, new CaseShareService.Period(null, null)))
                .extracting(CaseShareService.PublicAotCase::ticketNo).containsExactly("T4", "T1", "T3", "T2");
    }

    @Test
    void customersLifetimesAndKindsAreChecked() {
        assertThat(CaseShareService.customers(List.of(" Acme Foods ", "acme foods", "", "Blue Mart")))
                .containsExactly("Acme Foods", "Blue Mart");
        assertThat(CaseShareService.customers(List.of(" "))).isNull();
        assertThat(CaseShareService.days(null)).isEqualTo(30);
        assertThat(CaseShareService.days(365)).isEqualTo(365);
        assertThatThrownBy(() -> CaseShareService.days(0)).isInstanceOf(BadRequestException.class);
        assertThatThrownBy(() -> CaseShareService.days(366)).isInstanceOf(BadRequestException.class);
        assertThatThrownBy(() -> CaseShareService.kind("PAGE")).isInstanceOf(BadRequestException.class);
        assertThatThrownBy(() -> CaseShareService.requireView("payroll")).isInstanceOf(BadRequestException.class);
        assertThatThrownBy(() -> CaseShareService.cadence("YEARLY")).isInstanceOf(BadRequestException.class);
    }

    @Test
    void eachKindStoresWhatItNeedsAndNothingElse() {
        when(links.save(any(CaseShareLink.class))).thenAnswer(i -> i.getArgument(0));
        LocalDate first = LocalDate.of(2026, 10, 1);
        LocalDate last = LocalDate.of(2026, 10, 31);

        CaseShareService.LinkView sheet = service.create(new CaseShareService.CreateRequest(
                "SHEET", "cleaning", null, null, List.of("Acme Foods"), null, null, null, null, 7), UUID.randomUUID());
        assertThat(sheet.token()).hasSize(32);
        assertThat(sheet.customers()).containsExactly("Acme Foods");
        assertThat(sheet.expiresAt()).isBetween(OffsetDateTime.now().plusDays(7).minusMinutes(1),
                OffsetDateTime.now().plusDays(7).plusMinutes(1));

        // PCS is one customer's tab: no names kept. Internal mixes customers: names kept.
        CaseShareService.LinkView pcs = service.create(new CaseShareService.CreateRequest(
                "VIEW", null, "pcs", "delivery", List.of("Acme Foods"), "MONTHLY", first, last, null, null), null);
        assertThat(pcs.scope()).isEqualTo("DELIVERY");
        assertThat(pcs.customers()).isNull();
        assertThat(pcs.from()).isEqualTo(first);
        CaseShareService.LinkView internal = service.create(new CaseShareService.CreateRequest(
                "VIEW", null, "internal", null, List.of("Acme Foods"), "ALL", first, last, null, null), null);
        assertThat(internal.customers()).containsExactly("Acme Foods");
        assertThat(internal.scope()).isEqualTo("BOTH");
        assertThat(internal.from()).isNull();
        CaseShareService.LinkView mk = service.create(new CaseShareService.CreateRequest(
                "VIEW", null, "mk", "CLEANING", null, "WEEKLY", first, first.plusDays(4), null, null), null);
        // Every tab spans both boards, MK's too: its board is kept.
        assertThat(mk.scope()).isEqualTo("CLEANING");
        assertThatThrownBy(() -> service.create(new CaseShareService.CreateRequest(
                "VIEW", null, "mk", null, null, "MONTHLY", null, null, null, null), null))
                .isInstanceOf(BadRequestException.class);

        CaseShareService.LinkView one = service.create(new CaseShareService.CreateRequest(
                "CASE", "aotga", null, null, null, null, null, null, " T-1042 ", null), null);
        assertThat(one.caseKey()).isEqualTo("T-1042");
        assertThatThrownBy(() -> service.create(new CaseShareService.CreateRequest(
                "CASE", "mk", null, null, null, null, null, null, " ", null), null))
                .isInstanceOf(BadRequestException.class);
    }

    @Test
    void anOpenSheetLinkShowsTheLatestStoredSheetAndCountsTheVisit() {
        CaseShareLink link = link("SHEET", "cleaning");
        link.setCustomers("[\"Acme Foods\"]");
        when(links.findByToken("tok")).thenReturn(Optional.of(link));
        when(runs.latestStored(eq("CLEANING_PENDING"), any())).thenReturn(Optional.of(new CaseReportRunService.Stored(
                LocalDate.of(2026, 10, 7), LocalDateTime.of(2026, 10, 7, 9, 15),
                List.of(row(1, "Blue Mart"), row(2, "Acme Foods")))));

        CaseShareService.PublicView view = service.open("tok");

        assertThat(view.status()).isEqualTo(CaseShareService.Status.OK);
        assertThat(view.asOf()).isEqualTo(LocalDate.of(2026, 10, 7));
        assertThat(view.rows()).extracting(CaseShareService.PublicRow::serialNumber).containsExactly("SN2");
        verify(links).countView(eq(link.getId()), any());
        verify(runs, never()).rowsFor(anyString(), any(), any(Boolean.class));
    }

    /** A case put on hold moves to On Hold, and is still open; then it leaves every list. */
    @Test
    void aCaseLinkFollowsItsCaseAndThenShowsItsLastStateClosed() {
        CaseShareLink link = link("CASE", "cleaning");
        link.setCaseKey("item-7");
        when(links.findByToken("tok")).thenReturn(Optional.of(link));
        LocalDate oct5 = LocalDate.of(2026, 10, 5);
        LocalDate oct6 = LocalDate.of(2026, 10, 6);
        LocalDate oct7 = LocalDate.of(2026, 10, 7);
        when(runs.latestStored(anyString(), any())).thenReturn(Optional.empty());
        when(runs.latestStored(eq("CLEANING_PENDING"), any())).thenReturn(Optional.of(stored(oct7, row(1, "Acme"))));
        when(runs.latestStored(eq("ON_HOLD_PENDING"), any())).thenReturn(Optional.of(stored(oct7, row(7, "Acme"))));

        CaseShareService.PublicView open = service.open("tok");
        assertThat(open.caseStatus()).isEqualTo(CaseShareService.CaseStatus.OPEN);
        assertThat(open.rows()).extracting(CaseShareService.PublicRow::no).containsExactly(7);

        when(runs.latestStored(eq("ON_HOLD_PENDING"), any())).thenReturn(Optional.of(stored(oct7)));
        when(runs.recentStored(anyString(), any(), anyInt())).thenReturn(List.of());
        when(runs.recentStored(eq("CLEANING_PENDING"), any(), anyInt()))
                .thenReturn(List.of(stored(oct7), stored(oct6, row(7, "Acme")), stored(oct5, row(7, "Acme"))));

        CaseShareService.PublicView closed = service.open("tok");
        assertThat(closed.caseStatus()).isEqualTo(CaseShareService.CaseStatus.CLOSED);
        assertThat(closed.lastSeen()).isEqualTo(oct6);
        assertThat(closed.closedOn()).isEqualTo(oct7);
        assertThat(closed.rows()).hasSize(1);
    }

    @Test
    void stoppedAndExpiredLinksSaySoAndShowNothing() {
        CaseShareLink stopped = link("SHEET", "cleaning");
        stopped.setRevokedAt(OffsetDateTime.now().minusHours(1));
        CaseShareLink expired = link("SHEET", "cleaning");
        expired.setExpiresAt(OffsetDateTime.now().minusDays(1));
        when(links.findByToken("stopped")).thenReturn(Optional.of(stopped));
        when(links.findByToken("expired")).thenReturn(Optional.of(expired));

        assertThat(service.open("stopped").status()).isEqualTo(CaseShareService.Status.STOPPED);
        assertThat(service.open("expired").status()).isEqualTo(CaseShareService.Status.EXPIRED);
        assertThat(service.open("expired").rows()).isEmpty();
        assertThatThrownBy(() -> service.open("unknown")).isInstanceOf(ResourceNotFoundException.class);
        verify(links, never()).countView(any(), any());
        verify(runs, never()).latestStored(anyString(), any());

        when(links.findById(stopped.getId())).thenReturn(Optional.of(stopped));
        assertThatThrownBy(() -> service.extend(stopped.getId(), 30)).isInstanceOf(BadRequestException.class);
    }

    /**
     * A PM visit or site link: made only for one on the plan, read from the plan as it is,
     * and said to be gone - with nothing shown - once it is off it. Made-up site.
     */
    @Test
    void aPmVisitOrSiteLinkFollowsThePlanAndSaysWhenItIsGone() {
        var site = new PmPublicService.Site("Sample Site", "CLEANING", "Sample District", "Sample Province", "EAST",
                "Model X", 1, false);
        var shown = new PmPublicService.Shown(site, List.of(new PmPublicService.Visit("501", "PM1", 1,
                LocalDate.of(2026, 10, 20), null, null, "PLANNED", null, null)));
        when(pm.visit("501")).thenReturn(Optional.of(shown));
        when(pm.site("900")).thenReturn(Optional.empty());
        when(pm.site("901")).thenReturn(Optional.of(shown));
        when(pm.site(eq("901"), any(), any())).thenReturn(Optional.of(shown));
        when(links.save(any(CaseShareLink.class))).thenAnswer(i -> i.getArgument(0));

        CaseShareService.LinkView made = service.create(new CaseShareService.CreateRequest(
                "visit", null, null, null, null, null, null, null, " 501 ", null), null);
        assertThat(made.kind()).isEqualTo("VISIT");
        assertThat(made.sheet()).isEqualTo("pm");
        assertThat(made.caseKey()).isEqualTo("501");
        assertThatThrownBy(() -> service.create(new CaseShareService.CreateRequest(
                "SITE", null, null, null, null, null, null, null, "900", null), null))
                .isInstanceOf(BadRequestException.class);
        // A site shared for the year picked keeps its first and last day; for every visit, neither.
        CaseShareService.LinkView year = service.create(new CaseShareService.CreateRequest(
                "SITE", null, null, null, null, null, LocalDate.of(2026, 1, 1), LocalDate.of(2026, 12, 31), "901", null), null);
        assertThat(year.from()).isEqualTo(LocalDate.of(2026, 1, 1));
        assertThat(year.to()).isEqualTo(LocalDate.of(2026, 12, 31));
        assertThat(service.create(new CaseShareService.CreateRequest(
                "SITE", null, null, null, null, null, null, null, "901", null), null).from()).isNull();
        assertThatThrownBy(() -> service.create(new CaseShareService.CreateRequest(
                "SITE", null, null, null, null, null, LocalDate.of(2026, 1, 1), null, "901", null), null))
                .isInstanceOf(BadRequestException.class);

        CaseShareLink visit = link("VISIT", "pm");
        visit.setCaseKey("501");
        when(links.findByToken("visit")).thenReturn(Optional.of(visit));
        CaseShareService.PublicView open = service.open("visit");
        assertThat(open.caseStatus()).isEqualTo(CaseShareService.CaseStatus.OPEN);
        assertThat(open.pm().site().name()).isEqualTo("Sample Site");
        assertThat(open.rows()).isEmpty();

        CaseShareLink gone = link("SITE", "pm");
        gone.setCaseKey("900");
        when(pm.site(eq("900"), any(), any())).thenReturn(Optional.empty());
        when(links.findByToken("gone")).thenReturn(Optional.of(gone));
        CaseShareService.PublicView closed = service.open("gone");
        assertThat(closed.caseStatus()).isEqualTo(CaseShareService.CaseStatus.CLOSED);
        assertThat(closed.pm()).isNull();
    }

    /**
     * The links page: every link with what it shows and whether it still works; a case
     * keeps the tab it was shared from. Made-up titles.
     */
    @Test
    void theLinksPageListsEveryLinkWithItsTitleTabAndStatus() {
        when(links.save(any(CaseShareLink.class))).thenAnswer(i -> i.getArgument(0));
        CaseShareService.LinkView made = service.create(new CaseShareService.CreateRequest(
                "CASE", "cleaning", "pcs", null, null, null, null, null, "item-7", null,
                "  Case · B01 Somewhere · Pudu 1  "), null);
        assertThat(made.title()).isEqualTo("Case · B01 Somewhere · Pudu 1");
        assertThat(made.view()).isEqualTo("pcs");
        assertThat(made.status()).isEqualTo(CaseShareService.LinkStatus.ACTIVE);
        assertThatThrownBy(() -> service.create(new CaseShareService.CreateRequest(
                "CASE", "cleaning", "payroll", null, null, null, null, null, "item-7", null, null), null))
                .isInstanceOf(BadRequestException.class);

        CaseShareLink live = link("SHEET", "mk");
        CaseShareLink ended = link("SHEET", "mk");
        ended.setExpiresAt(OffsetDateTime.now().minusDays(1));
        CaseShareLink stopped = link("CASE", "mk");
        stopped.setRevokedAt(OffsetDateTime.now().minusHours(2));
        when(links.findAllByOrderByCreatedAtDesc()).thenReturn(List.of(live, ended, stopped));

        assertThat(service.all(null)).extracting(CaseShareService.LinkView::status).containsExactly(
                CaseShareService.LinkStatus.ACTIVE, CaseShareService.LinkStatus.EXPIRED, CaseShareService.LinkStatus.STOPPED);
        assertThat(service.all("expired")).extracting(CaseShareService.LinkView::id).containsExactly(ended.getId());
        assertThat(service.all("ALL")).hasSize(3);
        assertThatThrownBy(() -> service.all("someday")).isInstanceOf(BadRequestException.class);
    }

    /** Deleting: each id once, unknown ones skipped, nothing asked is refused, and a cap. */
    @Test
    void deletingRemovesTheLinksGivenAndSkipsUnknownOnes() {
        CaseShareLink one = link("SHEET", "mk");
        CaseShareLink two = link("CASE", "mk");
        UUID gone = UUID.randomUUID();
        when(links.findAllById(any())).thenReturn(List.of(one, two));

        CaseShareService.DeleteResult result = service.delete(Arrays.asList(one.getId(), two.getId(), one.getId(), null, gone));

        assertThat(result.deleted()).isEqualTo(2);
        @SuppressWarnings("unchecked")
        ArgumentCaptor<Iterable<UUID>> asked = ArgumentCaptor.forClass(Iterable.class);
        verify(links).findAllById(asked.capture());
        assertThat(asked.getValue()).containsExactly(one.getId(), two.getId(), gone);
        verify(links).deleteAll(List.of(one, two));

        assertThatThrownBy(() -> service.delete(List.of())).isInstanceOf(BadRequestException.class);
        assertThatThrownBy(() -> service.delete(null)).isInstanceOf(BadRequestException.class);
        List<UUID> tooMany = java.util.stream.Stream.generate(UUID::randomUUID).limit(CaseShareService.MAX_DELETE + 1).toList();
        assertThatThrownBy(() -> service.delete(tooMany)).isInstanceOf(BadRequestException.class);
    }

    private static CaseShareLink link(String kind, String sheet) {
        return CaseShareLink.builder().id(UUID.randomUUID()).token("tok").kind(kind).sheet(sheet)
                .createdAt(OffsetDateTime.now().minusDays(2)).expiresAt(OffsetDateTime.now().plusDays(28)).build();
    }
}
