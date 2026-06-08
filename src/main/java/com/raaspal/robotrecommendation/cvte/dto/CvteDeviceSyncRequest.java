package com.raaspal.robotrecommendation.cvte.dto;

/**
 * Search filters forwarded to the Kava device list API. All fields optional —
 * an empty request syncs the first page of devices visible to the app credentials.
 */
public record CvteDeviceSyncRequest(
        String factorySn,
        String deviceName,
        String orgCode
) {
}
