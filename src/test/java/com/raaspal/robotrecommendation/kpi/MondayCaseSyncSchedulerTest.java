package com.raaspal.robotrecommendation.kpi;

import com.raaspal.robotrecommendation.kpi.scheduler.MondayCaseSyncScheduler;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.ApplicationContext;
import org.springframework.test.context.TestPropertySource;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The scheduled monday sync only wires up when it is switched on, so the
 * enabled path — cron parsing and property resolution — is never exercised by
 * the default context. This boots it enabled to catch a bad cron or a missing
 * property at build time rather than on deploy.
 */
@SpringBootTest
@TestPropertySource(properties = {
        "app.kpi.monday.sync-enabled=true",
        "app.kpi.monday.sync-cron=0 30 1 * * *",
        "app.kpi.monday.sync-zone=Asia/Bangkok",
})
class MondayCaseSyncSchedulerTest {

    @Autowired
    private ApplicationContext context;

    @Test
    void schedulerIsRegisteredWhenEnabled() {
        assertThat(context.getBeansOfType(MondayCaseSyncScheduler.class)).hasSize(1);
    }
}
