package com.raaspal.robotrecommendation.cvte.dto;

import com.fasterxml.jackson.annotation.JsonFormat;
import com.raaspal.robotrecommendation.cvte.entity.CvteDevice;

import java.time.LocalDateTime;
import java.util.UUID;

/**
 * deviceId is serialised as a string (not a JSON number): Kava device IDs are
 * 19-digit longs that exceed JavaScript's safe integer range, so a numeric
 * JSON value gets silently rounded by the browser — causing poll-by-id calls
 * to target the wrong (corrupted) ID and fail with "device not found".
 */
public record CvteDeviceResponse(
        UUID id,
        @JsonFormat(shape = JsonFormat.Shape.STRING) Long deviceId,
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
