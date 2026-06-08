package com.raaspal.robotrecommendation.cvte.controller;

import com.raaspal.robotrecommendation.common.response.ApiResponse;
import com.raaspal.robotrecommendation.common.response.PagedResponse;
import com.raaspal.robotrecommendation.cvte.dto.CvteDeviceResponse;
import com.raaspal.robotrecommendation.cvte.dto.CvteDeviceSyncRequest;
import com.raaspal.robotrecommendation.cvte.entity.CvteDevice;
import com.raaspal.robotrecommendation.cvte.repository.CvteDeviceRepository;
import com.raaspal.robotrecommendation.cvte.service.CvteDeviceSyncService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Pageable;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * CVTE C3 online/offline status tracking — fully separate from the Robot/RobotSpec
 * module. Protected by the existing JWT filter (see SecurityConfig: anyRequest().authenticated()).
 */
@RestController
@RequestMapping("/api/v1/cvte/devices")
@RequiredArgsConstructor
public class CvteDeviceController {

    private final CvteDeviceRepository cvteDeviceRepository;
    private final CvteDeviceSyncService cvteDeviceSyncService;

    @GetMapping
    public ApiResponse<PagedResponse<CvteDeviceResponse>> getAll(
            @RequestParam(required = false) String factorySn,
            @RequestParam(required = false) String deviceName,
            @RequestParam(required = false) String orgCode,
            Pageable pageable
    ) {
        var page = cvteDeviceRepository.search(blankToNull(factorySn), blankToNull(deviceName), blankToNull(orgCode), pageable);
        return ApiResponse.success(PagedResponse.of(page, CvteDeviceResponse::from));
    }

    @PostMapping("/sync")
    public ApiResponse<List<CvteDeviceResponse>> sync(@Valid @RequestBody CvteDeviceSyncRequest request) {
        List<CvteDevice> synced = cvteDeviceSyncService.syncByCriteria(request.factorySn(), request.deviceName(), request.orgCode());
        return ApiResponse.success(
                "Synced " + synced.size() + " device(s) from Kava",
                synced.stream().map(CvteDeviceResponse::from).toList()
        );
    }

    @PostMapping("/poll-now")
    public ApiResponse<List<CvteDeviceResponse>> pollNow() {
        List<CvteDevice> polled = cvteDeviceSyncService.pollAll();
        return ApiResponse.success(
                "Polled " + polled.size() + " device(s)",
                polled.stream().map(CvteDeviceResponse::from).toList()
        );
    }

    @PostMapping("/{deviceId}/poll-now")
    public ApiResponse<CvteDeviceResponse> pollOne(@PathVariable Long deviceId) {
        CvteDevice device = cvteDeviceSyncService.pollOne(deviceId);
        return ApiResponse.success("Device polled", CvteDeviceResponse.from(device));
    }

    private static String blankToNull(String value) {
        return (value == null || value.isBlank()) ? null : value.trim();
    }
}
