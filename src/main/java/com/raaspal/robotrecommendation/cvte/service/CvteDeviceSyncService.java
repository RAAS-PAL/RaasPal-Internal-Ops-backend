package com.raaspal.robotrecommendation.cvte.service;

import com.raaspal.robotrecommendation.common.exception.ResourceNotFoundException;
import com.raaspal.robotrecommendation.cvte.client.KavaApiClient;
import com.raaspal.robotrecommendation.cvte.client.KavaApiResult;
import com.raaspal.robotrecommendation.cvte.client.KavaDeviceStatus;
import com.raaspal.robotrecommendation.cvte.entity.CvteDevice;
import com.raaspal.robotrecommendation.cvte.repository.CvteDeviceRepository;
import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.List;

/**
 * Pulls CVTE C3 device status from the Kava Open Gateway API and upserts it
 * into [[CvteDevice]] — kept fully separate from the Robot/RobotSpec entities.
 */
@Service
@RequiredArgsConstructor
public class CvteDeviceSyncService {

    private static final Logger log = LoggerFactory.getLogger(CvteDeviceSyncService.class);
    private static final int SEARCH_PAGE_SIZE = 50;

    private final KavaApiClient kavaApiClient;
    private final CvteDeviceRepository cvteDeviceRepository;

    /** Searches Kava by factory SN / device name / org code and upserts every match found. */
    @Transactional
    public List<CvteDevice> syncByCriteria(String factorySn, String deviceName, String orgCode) {
        requireConfigured();
        KavaApiResult<List<KavaDeviceStatus>> result = kavaApiClient.searchDevices(factorySn, deviceName, orgCode, SEARCH_PAGE_SIZE);
        return result.data().stream()
                .map(status -> upsert(status, orgCode, result.summary()))
                .toList();
    }

    /** Re-polls every device already known locally, refreshing status from the device detail endpoint. */
    @Transactional
    public List<CvteDevice> pollAll() {
        requireConfigured();
        return cvteDeviceRepository.findAll().stream()
                .map(this::pollOne)
                .toList();
    }

    /** Re-polls a single known device by its Kava device ID. */
    @Transactional
    public CvteDevice pollOne(Long deviceId) {
        requireConfigured();
        CvteDevice existing = cvteDeviceRepository.findByDeviceId(deviceId)
                .orElseThrow(() -> new ResourceNotFoundException("CvteDevice", "deviceId", deviceId));
        return pollOne(existing);
    }

    private CvteDevice pollOne(CvteDevice existing) {
        try {
            KavaApiResult<KavaDeviceStatus> result = kavaApiClient.getDeviceDetail(existing.getDeviceId());
            if (result.data() == null) {
                existing.setLastCheckedAt(LocalDateTime.now());
                existing.setLastMessage(result.summary());
                return cvteDeviceRepository.save(existing);
            }
            return upsert(result.data(), existing.getOrgCode(), result.summary());
        } catch (RuntimeException e) {
            log.warn("Failed to poll CVTE device {} ({}): {}", existing.getDeviceId(), existing.getFactorySn(), e.getMessage());
            existing.setLastCheckedAt(LocalDateTime.now());
            existing.setLastMessage("Poll failed: " + e.getMessage());
            return cvteDeviceRepository.save(existing);
        }
    }

    private CvteDevice upsert(KavaDeviceStatus status, String fallbackOrgCode, String lastMessage) {
        CvteDevice device = cvteDeviceRepository.findByDeviceId(status.deviceId())
                .or(() -> cvteDeviceRepository.findByFactorySn(status.factorySn()))
                .orElseGet(CvteDevice::new);

        if (device.getOrgCode() == null) {
            device.setOrgCode(fallbackOrgCode);
        }

        device.setDeviceId(status.deviceId());
        device.setFactorySn(status.factorySn());
        device.setDeviceName(status.deviceName());
        device.setOnlineStatus(status.online());
        device.setRunningState(status.runningState());
        device.setBatteryPercentage(status.batteryPercentage());
        device.setLastCheckedAt(LocalDateTime.now());
        device.setLastMessage(lastMessage);

        return cvteDeviceRepository.save(device);
    }

    private void requireConfigured() {
        if (!kavaApiClient.isConfigured()) {
            throw new IllegalStateException("Kava API credentials are not configured (set CVTE_KAVA_APP_ID and CVTE_KAVA_APP_SECRET).");
        }
    }
}
