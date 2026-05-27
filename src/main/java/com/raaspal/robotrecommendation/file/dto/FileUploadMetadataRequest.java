package com.raaspal.robotrecommendation.file.dto;

import jakarta.validation.constraints.Size;

import java.util.UUID;

public record FileUploadMetadataRequest(
        @Size(max = 50)
        String entityType,

        UUID entityId
) {
}
