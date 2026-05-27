package com.raaspal.robotrecommendation.recommendation.controller;

import com.raaspal.robotrecommendation.common.response.ApiResponse;
import com.raaspal.robotrecommendation.common.response.PagedResponse;
import com.raaspal.robotrecommendation.recommendation.dto.GenerateRecommendationRequest;
import com.raaspal.robotrecommendation.recommendation.dto.RecommendationResponse;
import com.raaspal.robotrecommendation.recommendation.service.RecommendationService;
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

import java.util.UUID;

@RestController
@RequestMapping("/api/v1/recommendations")
@RequiredArgsConstructor
public class RecommendationController {

    private final RecommendationService recommendationService;

    @GetMapping
    public ApiResponse<PagedResponse<RecommendationResponse>> getAll(Pageable pageable) {
        return ApiResponse.success(PagedResponse.of(recommendationService.getAll(pageable)));
    }

    @GetMapping("/{id}")
    public ApiResponse<RecommendationResponse> getById(@PathVariable UUID id) {
        return ApiResponse.success(recommendationService.getById(id));
    }

    @PostMapping("/generate/{requirementId}")
    public ApiResponse<RecommendationResponse> generate(
            @PathVariable UUID requirementId,
            @Valid @RequestBody(required = false) GenerateRecommendationRequest request,
            @RequestParam(required = false) UUID createdById
    ) {
        return ApiResponse.success(
                "Recommendation generated",
                recommendationService.generate(requirementId, request, createdById)
        );
    }
}
