package com.raaspal.robotrecommendation.telemetry.adapters.autoxing;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.time.Instant;
import java.util.HashSet;
import java.util.Optional;
import java.util.Set;

/**
 * Which robot IDs exist on the RAAS PAL AutoXing account, cached for a few minutes.
 *
 * <p>Used to refuse a registration whose serial AutoXing does not know. AutoXing serials
 * mix a lowercase {@code l} and a capital {@code I} ({@code 2382410c042997l}), which are
 * indistinguishable in most fonts; a typo stored at registration would otherwise surface
 * weeks later as a report that "cannot find the robot".
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class AutoxingFleetDirectory {

    private static final Duration TTL = Duration.ofMinutes(5);

    private final AutoxingApiClient apiClient;
    private final AutoxingReportService reportService;

    private volatile Set<String> ids = Set.of();
    private volatile Instant loadedAt = Instant.EPOCH;

    /**
     * True or false when AutoXing could be asked; empty when it could not (credentials
     * missing, AutoXing down). Callers must not block a registration on an empty answer -
     * an outage at AutoXing is not a reason to stop the team registering a robot.
     */
    public Optional<Boolean> knows(String robotId) {
        if (robotId == null || robotId.isBlank() || !apiClient.isConfigured()) {
            return Optional.empty();
        }
        if (Instant.now().isAfter(loadedAt.plus(TTL)) || !ids.contains(robotId.trim())) {
            // Reload on a miss too: a robot added to AutoXing a minute ago must not be refused.
            try {
                Set<String> fresh = new HashSet<>();
                reportService.withReauth(apiClient::getRobotFleet)
                        .forEach(n -> fresh.add(n.path("robotId").asText("")));
                ids = fresh;
                loadedAt = Instant.now();
            } catch (Exception e) {
                log.warn("Could not read the AutoXing fleet to check robot {}: {}", robotId, e.getMessage());
                return Optional.empty();
            }
        }
        return Optional.of(ids.contains(robotId.trim()));
    }
}
