package com.raaspal.robotrecommendation.pm;

import com.raaspal.robotrecommendation.pm.dto.PmFilter;
import com.raaspal.robotrecommendation.pm.service.PmPlanningService;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Method;

import static org.assertj.core.api.Assertions.assertThat;

/** Which monday groups mark a contract as ended, for the No-date list's label. */
class PmContractEndedTest {

    private static boolean ended(String group) throws Exception {
        Method method = PmPlanningService.class.getDeclaredMethod("contractEnded", String.class);
        method.setAccessible(true);
        return (boolean) method.invoke(null, group);
    }

    @Test
    void readsTheEndedGroupsWhateverWordsSurroundIt() throws Exception {
        assertThat(ended("หมดสัญญา")).isTrue();
        assertThat(ended("Customer A Phase 4 หมดสัญญา")).isTrue();
        assertThat(ended("หมดสัญญา ในการรับประกัน")).isTrue();
    }

    /** The planner leaves ended contracts out unless the switch asks for them (user, 2026-10-08). */
    @Test
    void theFilterLeavesEndedContractsOutUnlessAskedFor() {
        PmFilter plain = PmFilter.of(null, null, null, null, null, null, null, null);
        assertThat(plain.includeEnded()).isFalse();
        assertThat(plain.withEnded("show").includeEnded()).isTrue();
        assertThat(plain.withEnded(" SHOW ").includeEnded()).isTrue();
        assertThat(plain.withEnded("yes").includeEnded()).isFalse();
        assertThat(plain.withEnded(null).includeEnded()).isFalse();
    }

    @Test
    void leavesLiveProgrammesAndMissingGroupsAlone() throws Exception {
        assertThat(ended("PM Customer B 72 สาขา")).isFalse();
        assertThat(ended("Robot")).isFalse();
        assertThat(ended(null)).isFalse();
    }
}
