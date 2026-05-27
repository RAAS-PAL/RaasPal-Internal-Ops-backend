package com.raaspal.robotrecommendation.file.controller;

import com.raaspal.robotrecommendation.common.response.ApiResponse;
import com.raaspal.robotrecommendation.common.response.PagedResponse;
import com.raaspal.robotrecommendation.file.dto.FileUploadMetadataRequest;
import com.raaspal.robotrecommendation.file.dto.FileUploadResponse;
import com.raaspal.robotrecommendation.file.entity.FileUpload;
import com.raaspal.robotrecommendation.file.service.FileUploadService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.core.io.Resource;
import org.springframework.data.domain.Pageable;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

import java.util.UUID;

@RestController
@RequestMapping("/api/v1/files")
@RequiredArgsConstructor
public class FileController {

    private final FileUploadService fileUploadService;

    @GetMapping
    public ApiResponse<PagedResponse<FileUploadResponse>> getAll(Pageable pageable) {
        return ApiResponse.success(PagedResponse.of(fileUploadService.getAll(pageable)));
    }

    @GetMapping("/{id}")
    public ApiResponse<FileUploadResponse> getById(@PathVariable UUID id) {
        return ApiResponse.success(fileUploadService.getById(id));
    }

    @PostMapping(value = "/upload", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public ApiResponse<FileUploadResponse> upload(
            @RequestParam("file") MultipartFile file,
            @RequestParam(required = false) String entityType,
            @RequestParam(required = false) UUID entityId,
            @RequestParam(required = false) UUID uploadedById
    ) {
        FileUploadMetadataRequest metadata = new FileUploadMetadataRequest(entityType, entityId);
        return ApiResponse.success("File uploaded", fileUploadService.store(file, metadata, uploadedById));
    }

    @GetMapping("/{id}/download")
    public ResponseEntity<Resource> download(@PathVariable UUID id) {
        FileUpload fileUpload = fileUploadService.getEntity(id);
        Resource resource = fileUploadService.loadAsResource(id);
        String contentType = fileUpload.getContentType() == null
                ? MediaType.APPLICATION_OCTET_STREAM_VALUE
                : fileUpload.getContentType();
        String filename = fileUpload.getOriginalFilename().replace("\"", "");

        return ResponseEntity.ok()
                .contentType(MediaType.parseMediaType(contentType))
                .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=\"" + filename + "\"")
                .body(resource);
    }

    @PatchMapping("/{id}/metadata")
    public ApiResponse<FileUploadResponse> attachToEntity(
            @PathVariable UUID id,
            @Valid @RequestBody FileUploadMetadataRequest request
    ) {
        return ApiResponse.success("File metadata updated", fileUploadService.attachToEntity(id, request));
    }
}
