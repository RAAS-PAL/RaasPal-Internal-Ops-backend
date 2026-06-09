package com.raaspal.robotrecommendation.translation.controller;

import com.raaspal.robotrecommendation.ai.service.TranslationAiService;
import com.raaspal.robotrecommendation.common.response.ApiResponse;
import com.raaspal.robotrecommendation.translation.dto.TranslationRequest;
import com.raaspal.robotrecommendation.translation.dto.TranslationResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/translate")
@RequiredArgsConstructor
public class TranslationController {

    private final TranslationAiService translationAiService;

    @PostMapping("/thai")
    public ApiResponse<TranslationResponse> translateToThai(@RequestBody TranslationRequest request) {
        var translations = translationAiService.translateToThai(request.texts());
        return ApiResponse.success(new TranslationResponse(translations));
    }
}
