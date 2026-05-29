package com.raaspal.robotrecommendation.robot.controller;

import com.raaspal.robotrecommendation.common.response.ApiResponse;
import com.raaspal.robotrecommendation.common.response.PagedResponse;
import com.raaspal.robotrecommendation.common.enums.RobotType;
import com.raaspal.robotrecommendation.common.enums.TestStatus;
import com.raaspal.robotrecommendation.robot.dto.RobotImportResult;
import com.raaspal.robotrecommendation.robot.dto.RobotRequest;
import com.raaspal.robotrecommendation.robot.dto.RobotResponse;
import com.raaspal.robotrecommendation.robot.service.RobotImportService;
import com.raaspal.robotrecommendation.robot.service.RobotService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Pageable;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

import java.util.UUID;

@RestController
@RequestMapping("/api/v1/robots")
@RequiredArgsConstructor
public class RobotController {

    private final RobotService robotService;
    private final RobotImportService robotImportService;

    @GetMapping
    public ApiResponse<PagedResponse<RobotResponse>> getAll(Pageable pageable) {
        return ApiResponse.success(PagedResponse.of(robotService.getAll(pageable)));
    }

    @GetMapping("/{id}")
    public ApiResponse<RobotResponse> getById(@PathVariable UUID id) {
        return ApiResponse.success(robotService.getById(id));
    }

    @PostMapping
    public ApiResponse<RobotResponse> create(@Valid @RequestBody RobotRequest request) {
        return ApiResponse.success("Robot created", robotService.create(request));
    }

    @PostMapping(value = "/import", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public ApiResponse<RobotImportResult> importCatalog(
            @RequestParam("file") MultipartFile file,
            @RequestParam(required = false, defaultValue = "CLEANING") RobotType robotType,
            @RequestParam(required = false, defaultValue = "DRAFT") TestStatus testStatus
    ) {
        return ApiResponse.success(
                "Robot catalog imported",
                robotImportService.importCatalog(file, robotType, testStatus)
        );
    }

    @PutMapping("/{id}")
    public ApiResponse<RobotResponse> update(
            @PathVariable UUID id,
            @Valid @RequestBody RobotRequest request
    ) {
        return ApiResponse.success("Robot updated", robotService.update(id, request));
    }

    @DeleteMapping("/{id}")
    public ApiResponse<Void> delete(@PathVariable UUID id) {
        robotService.delete(id);
        return ApiResponse.success("Robot deleted");
    }
}
