package com.raaspal.robotrecommendation.cvte.dto;

import com.raaspal.robotrecommendation.cvte.entity.CvteDevice;

import java.time.LocalDateTime;
import java.util.UUID;

public record CvteDeviceResponse(
        UUID id,
        Long deviceId,
        String factorySn,
        String deviceName,
        String orgCode,
        Boolean onlineStatus,
        String runningState,
        Double batteryPercentage,
        LocalDateTime lastCheckedAt,
        String lastMessage
) {
    public static CvteDeviceResponse from(CvteDevice device) {
        return new CvteDeviceResponse(
                device.getId(),
                device.getDeviceId(),
                device.getFactorySn(),
                device.getDeviceName(),
                device.getOrgCode(),
                device.getOnlineStatus(),
                device.getRunningState(),
                device.getBatteryPercentage(),
                device.getLastCheckedAt(),
                device.getLastMessage()
        );
    }
}
