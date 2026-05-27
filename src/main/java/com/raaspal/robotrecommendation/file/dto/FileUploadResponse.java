package com.raaspal.robotrecommendation.file.dto;

import com.raaspal.robotrecommendation.file.entity.FileUpload;

import java.time.LocalDateTime;
import java.util.UUID;

public record FileUploadResponse(
        UUID id,
        String originalFilename,
        String storedFilename,
        String contentType,
        Long fileSize,
        String entityType,
        UUID entityId,
        UUID uploadedById,
        boolean locked,
        LocalDateTime createdAt
) {
    public static FileUploadResponse from(FileUpload fileUpload) {
        UUID uploadedById = fileUpload.getUploadedBy() == null ? null : fileUpload.getUploadedBy().getId();

        return new FileUploadResponse(
                fileUpload.getId(),
                fileUpload.getOriginalFilename(),
                fileUpload.getStoredFilename(),
                fileUpload.getContentType(),
                fileUpload.getFileSize(),
                fileUpload.getEntityType(),
                fileUpload.getEntityId(),
                uploadedById,
                fileUpload.isLocked(),
                fileUpload.getCreatedAt()
        );
    }
}
