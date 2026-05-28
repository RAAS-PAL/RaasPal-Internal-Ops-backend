package com.raaspal.robotrecommendation.ai.controller;

import com.raaspal.robotrecommendation.ai.dto.AiPromptPreview;
import com.raaspal.robotrecommendation.ai.service.AiPromptPreviewService;
import com.raaspal.robotrecommendation.common.response.ApiResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/ai")
@RequiredArgsConstructor
public class AiController {

    private final AiPromptPreviewService aiPromptPreviewService;

    @GetMapping("/prompt-preview")
    public ApiResponse<AiPromptPreview> getPromptPreview() {
        return ApiResponse.success(aiPromptPreviewService.getPromptPreview());
    }
}
