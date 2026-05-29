package com.raaspal.robotrecommendation.ai.service;

import com.raaspal.robotrecommendation.ai.dto.ExtractedRequirementData;
import com.raaspal.robotrecommendation.common.enums.RobotType;
import com.raaspal.robotrecommendation.file.entity.FileUpload;

public interface RequirementExtractionService {

    ExtractedRequirementData extract(FileUpload fileUpload, RobotType robotType);
}
