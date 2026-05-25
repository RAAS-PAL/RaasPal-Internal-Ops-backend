package com.raaspal.robotrecommendation.file.repository;

import com.raaspal.robotrecommendation.file.entity.FileUpload;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.UUID;

public interface FileUploadRepository extends JpaRepository<FileUpload, UUID> {

    List<FileUpload> findByEntityTypeAndEntityId(String entityType, UUID entityId);

    List<FileUpload> findByUploadedById(UUID uploadedById);
}