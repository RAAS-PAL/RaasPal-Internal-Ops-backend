package com.raaspal.robotrecommendation.casereport.customer;

import lombok.RequiredArgsConstructor;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Reads the robot list a minute after start and again before it goes stale, so neither a
 * pending tab nor a public link waits for monday (about 18 s) to learn whose a case is.
 * Off in tests, which have no monday token.
 */
@Component
@RequiredArgsConstructor
@ConditionalOnProperty(name = "app.casereport.robot-board.warm-up", havingValue = "true", matchIfMissing = true)
public class RobotListWarmer {

    private final RobotCustomers robots;

    @Scheduled(initialDelayString = "PT1M", fixedDelayString = "PT5H")
    void keepWarm() {
        robots.refresh();
    }
}
