package com.raaspal.robotrecommendation.telemetry.scheduler;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.ApplicationContext;
import org.springframework.test.context.TestPropertySource;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The scheduled telemetry sync only wires up when it is switched on, so the
 * enabled path — cron expression parsing and {@code @Value} resolution — is
 * never exercised by the default context test. This boots it enabled to catch a
 * bad cron or a missing property at build time rather than on deploy.
 */
@SpringBootTest
@TestPropertySource(properties = {
        "app.telemetry.sync-enabled=true",
        "app.telemetry.sync-cron=0 0 3 * * *",
        "app.telemetry.sync-zone=Asia/Bangkok",
        "app.telemetry.sync-lookback-days=3",
})
class TelemetrySyncSchedulerTest {

    @Autowired
    private ApplicationContext context;

    @Test
    void schedulerIsRegisteredWhenEnabled() {
        assertThat(context.getBeansOfType(TelemetrySyncScheduler.class)).hasSize(1);
    }
}
