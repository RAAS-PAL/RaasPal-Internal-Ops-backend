package com.raaspal.robotrecommendation.requirement.controller;

import com.raaspal.robotrecommendation.auth.security.UserPrincipal;
import com.raaspal.robotrecommendation.common.response.ApiResponse;
import com.raaspal.robotrecommendation.common.response.PagedResponse;
import com.raaspal.robotrecommendation.requirement.dto.ExtractRequirementRequest;
import com.raaspal.robotrecommendation.requirement.dto.RequirementRequest;
import com.raaspal.robotrecommendation.requirement.dto.RequirementResponse;
import com.raaspal.robotrecommendation.requirement.service.RequirementService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Pageable;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.UUID;

@RestController
@RequestMapping("/api/v1/requirements")
@RequiredArgsConstructor
public class RequirementController {

    private final RequirementService requirementService;

    @GetMapping
    public ApiResponse<PagedResponse<RequirementResponse>> getAll(Pageable pageable) {
        return ApiResponse.success(PagedResponse.of(requirementService.getAll(pageable)));
    }

    @GetMapping("/{id}")
    public ApiResponse<RequirementResponse> getById(@PathVariable UUID id) {
        return ApiResponse.success(requirementService.getById(id));
    }

    @PostMapping
    public ApiResponse<RequirementResponse> create(
            @Valid @RequestBody RequirementRequest request,
            @AuthenticationPrincipal UserPrincipal principal
    ) {
        return ApiResponse.success("Requirement created",
                requirementService.create(request, principal.getId()));
    }

    @PutMapping("/{id}")
    public ApiResponse<RequirementResponse> update(
            @PathVariable UUID id,
            @Valid @RequestBody RequirementRequest request
    ) {
        return ApiResponse.success("Requirement updated", requirementService.update(id, request));
    }

    @PostMapping("/extract-from-file/{fileId}")
    public ApiResponse<RequirementResponse> extractFromFile(
            @PathVariable UUID fileId,
            @Valid @RequestBody ExtractRequirementRequest request,
            @AuthenticationPrincipal UserPrincipal principal
    ) {
        return ApiResponse.success("Requirement extracted",
                requirementService.extractFromFile(fileId, request, principal.getId()));
    }
}
