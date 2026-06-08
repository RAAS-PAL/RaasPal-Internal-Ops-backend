package com.raaspal.robotrecommendation.cvte.service;

import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Optional background refresh of known CVTE devices' online/offline status.
 * Disabled unless CVTE_KAVA_POLLING_ENABLED=true is set — the feature works
 * fine on manual sync/poll-now alone otherwise.
 */
@Component
@ConditionalOnProperty(name = "app.cvte.kava.polling-enabled", havingValue = "true")
@RequiredArgsConstructor
public class CvteDevicePollingScheduler {

    private static final Logger log = LoggerFactory.getLogger(CvteDevicePollingScheduler.class);

    private final CvteDeviceSyncService cvteDeviceSyncService;

    @Scheduled(fixedDelayString = "${app.cvte.kava.polling-interval-ms:60000}")
    public void pollKnownDevices() {
        try {
            int count = cvteDeviceSyncService.pollAll().size();
            log.debug("CVTE scheduled poll refreshed {} device(s)", count);
        } catch (RuntimeException e) {
            log.warn("CVTE scheduled poll failed: {}", e.getMessage());
        }
    }
}
