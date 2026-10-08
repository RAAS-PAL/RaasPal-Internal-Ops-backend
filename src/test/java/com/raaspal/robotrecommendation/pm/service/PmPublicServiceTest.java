package com.raaspal.robotrecommendation.pm.service;

import com.raaspal.robotrecommendation.pm.repository.PmVisitRepository;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/** A PM visit and site as a public link shows them. Made-up sites and visits. */
class PmPublicServiceTest {

    private static final LocalDate TODAY = LocalDate.of(2026, 10, 8);

    @Test
    void aSiteShowsWhereItIsAndEachVisitsStandingButNeverItsContact() {
        var shown = PmPublicService.shown(List.of(
                row("101", LocalDate.of(2026, 10, 2), "PLANNED", "Somchai"),
                row("102", LocalDate.of(2026, 10, 20), "PLANNED", null),
                row("103", null, "UNPLANNED", " ")), TODAY).orElseThrow();

        assertThat(shown.site().name()).isEqualTo("Sample Site");
        assertThat(shown.site().district()).isEqualTo("Sample District");
        assertThat(shown.site().contractEnded()).isTrue();
        assertThat(shown.visits()).extracting(PmPublicService.Visit::status)
                .containsExactly("OVERDUE", "PLANNED", "UNPLANNED");
        assertThat(shown.visits().get(0).daysOverdue()).isEqualTo(6);
        assertThat(shown.visits()).extracting(PmPublicService.Visit::engineer).containsExactly("Somchai", null, null);
        // The public records have no field for the contact at all.
        assertThat(PmPublicService.Site.class.getRecordComponents())
                .extracting(c -> c.getName()).doesNotContain("contactPhone", "contactEmail", "customerName");
    }

    /** A site shared for a year: that year's visits and the ones not dated yet; the site even with none. */
    @Test
    void aYearsLinkShowsThatYearsVisitsAndTheUndatedOnes() {
        var rows = List.of(
                row("201", LocalDate.of(2025, 12, 30), "COMPLETED", null),
                row("202", LocalDate.of(2026, 3, 1), "COMPLETED", null),
                row("203", LocalDate.of(2027, 1, 4), "PLANNED", null),
                row("204", null, "UNPLANNED", null));
        var year = PmPublicService.shown(rows, TODAY, LocalDate.of(2026, 1, 1), LocalDate.of(2026, 12, 31)).orElseThrow();
        assertThat(year.visits()).extracting(PmPublicService.Visit::jobNo).containsExactly("202", "204");

        var none = PmPublicService.shown(rows.subList(0, 1), TODAY, LocalDate.of(2026, 1, 1),
                LocalDate.of(2026, 12, 31)).orElseThrow();
        assertThat(none.site().name()).isEqualTo("Sample Site");
        assertThat(none.visits()).isEmpty();
    }

    @Test
    void nothingIsShownOnceTheVisitIsOffThePlan() {
        assertThat(PmPublicService.shown(List.of(), TODAY)).isEmpty();
    }

    private static PmVisitRepository.VisitRow row(String id, LocalDate plan, String bucket, String owner) {
        return new PmVisitRepository.VisitRow() {
            public UUID getVisitId() { return UUID.randomUUID(); }
            public String getItemId() { return id; }
            public String getVisitName() { return "PM" + id; }
            public Integer getPmSequence() { return 1; }
            public LocalDate getPlanDate() { return plan; }
            public LocalDate getActionDate() { return null; }
            public String getTimeText() { return null; }
            public String getStatusRaw() { return bucket; }
            public String getStatusBucket() { return bucket; }
            public String getOwnerNames() { return owner; }
            public UUID getContractId() { return null; }
            public String getItemName() { return "Sample Site"; }
            public String getCustomerName() { return "Contact Person"; }
            public String getContactPhone() { return "0800000000"; }
            public String getContactEmail() { return "contact@example.com"; }
            public String getDistrict() { return "Sample District"; }
            public String getSiteItemId() { return "900"; }
            public String getProject() { return null; }
            public String getServiceLine() { return "CLEANING"; }
            public String getCompany() { return "Sample"; }
            public String getProvince() { return "Sample Province"; }
            public String getRegion() { return "EAST"; }
            public String getZone() { return null; }
            public String getRobotModel() { return "Model X"; }
            public Integer getRobotCount() { return 2; }
            public String getContractType() { return null; }
            public String getContractGroup() { return "หมดสัญญา"; }
        };
    }
}
