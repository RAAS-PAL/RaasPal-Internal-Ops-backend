package com.raaspal.robotrecommendation.pm;

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

    @Test
    void leavesLiveProgrammesAndMissingGroupsAlone() throws Exception {
        assertThat(ended("PM Customer B 72 สาขา")).isFalse();
        assertThat(ended("Robot")).isFalse();
        assertThat(ended(null)).isFalse();
    }
}
