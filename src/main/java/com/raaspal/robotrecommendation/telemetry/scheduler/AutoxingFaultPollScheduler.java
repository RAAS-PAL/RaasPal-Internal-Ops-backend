package com.raaspal.robotrecommendation.telemetry.scheduler;

import com.raaspal.robotrecommendation.telemetry.adapters.autoxing.AutoxingFaultPollService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Polls the AutoXing fleet for faults every few seconds (default 10).
 *
 * <p>⚠️ Off unless {@code app.autoxing.fault-poll.enabled} is true, like every other
 * scheduler here - and for a sharper reason: local development points at the production
 * database, so a local backend polling too would write the same history twice. The unique
 * index on open faults stops duplicate rows, but the second poller would still close and
 * reopen faults on its own clock. Turn it on in exactly one place: Lightsail.
 *
 * <p>Fixed delay, not fixed rate: a slow AutoXing response pushes the next poll back
 * instead of stacking polls on top of each other.
 */
@Slf4j
@Component
@RequiredArgsConstructor
@ConditionalOnProperty(name = "app.autoxing.fault-poll.enabled", havingValue = "true")
public class AutoxingFaultPollScheduler {

    private final AutoxingFaultPollService pollService;

    @Scheduled(fixedDelayString = "${app.autoxing.fault-poll.interval-ms:10000}",
            initialDelayString = "${app.autoxing.fault-poll.initial-delay-ms:20000}")
    public void poll() {
        try {
            pollService.poll();
        } catch (Exception e) {
            // Never let one bad poll stop the schedule.
            log.error("AutoXing fault poll threw; the next poll will retry", e);
        }
    }
}
