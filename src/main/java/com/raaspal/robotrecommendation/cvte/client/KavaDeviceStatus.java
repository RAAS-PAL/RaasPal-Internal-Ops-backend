package com.raaspal.robotrecommendation.cvte.client;

/**
 * Normalised device status fields read from the Kava API — covers both the
 * device list (POST /v1/device/page) and device detail (GET /v1/device/detail/{id})
 * shapes, which encode "online" differently (boolean vs. integer).
 */
public record KavaDeviceStatus(
        Long deviceId,
        String factorySn,
        String deviceName,
        String runningState,
        Double batteryPercentage,
        Boolean online
) {
}
