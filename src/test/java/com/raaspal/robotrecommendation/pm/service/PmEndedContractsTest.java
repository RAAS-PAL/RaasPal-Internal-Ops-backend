package com.raaspal.robotrecommendation.pm.service;

import com.raaspal.robotrecommendation.pm.dto.PmFilter;
import com.raaspal.robotrecommendation.pm.repository.PmVisitRepository;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Ended contracts - a monday group saying หมดสัญญา - are not tracked by the planner: their
 * visits are left out of every list and count unless the switch asks for them. Made-up sites.
 */
class PmEndedContractsTest {

    private final PmVisitRepository visits = mock(PmVisitRepository.class);
    private final PmPlanningService service = new PmPlanningService(visits, null);

    @Test
    void anEndedContractsVisitsAreLeftOutUnlessAskedFor() {
        when(visits.findUndated(any(), any(), any(), any(), any(), any(), any())).thenReturn(List.of(
                row("live", "PM Sample 12 sites"),
                row("ended", "Sample หมดสัญญา"),
                row("ended-warranty", "หมดสัญญา ในการรับประกัน")));
        PmFilter filter = PmFilter.of(null, null, null, null, null, null, null, null);

        var tracked = service.undated(filter);
        assertThat(tracked.rows()).extracting(r -> r.itemId()).containsExactly("live");
        verify(visits).countUndated(null, false);

        var all = service.undated(filter.withEnded("show"));
        assertThat(all.rows()).extracting(r -> r.itemId()).containsExactly("live", "ended", "ended-warranty");
        verify(visits).countUndated(eq(null), eq(true));
    }

    private static PmVisitRepository.VisitRow row(String id, String group) {
        return new PmVisitRepository.VisitRow() {
            public UUID getVisitId() { return UUID.randomUUID(); }
            public String getItemId() { return id; }
            public String getVisitName() { return "PM " + id; }
            public Integer getPmSequence() { return 1; }
            public LocalDate getPlanDate() { return null; }
            public LocalDate getActionDate() { return null; }
            public String getTimeText() { return null; }
            public String getStatusRaw() { return null; }
            public String getStatusBucket() { return "UNPLANNED"; }
            public String getOwnerNames() { return null; }
            public UUID getContractId() { return UUID.nameUUIDFromBytes(id.getBytes()); }
            public String getItemName() { return "Site " + id; }
            public String getCustomerName() { return null; }
            public String getContactPhone() { return null; }
            public String getContactEmail() { return null; }
            public String getDistrict() { return null; }
            public String getSiteItemId() { return id; }
            public String getProject() { return null; }
            public String getServiceLine() { return "CLEANING"; }
            public String getCompany() { return "Sample"; }
            public String getProvince() { return null; }
            public String getRegion() { return null; }
            public String getZone() { return null; }
            public String getRobotModel() { return null; }
            public Integer getRobotCount() { return 1; }
            public String getContractType() { return null; }
            public String getContractGroup() { return group; }
        };
    }
}
