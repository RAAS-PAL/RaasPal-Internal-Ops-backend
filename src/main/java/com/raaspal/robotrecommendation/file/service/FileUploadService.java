package com.raaspal.robotrecommendation.file.service;

import com.raaspal.robotrecommendation.common.exception.ResourceNotFoundException;
import com.raaspal.robotrecommendation.file.dto.FileUploadMetadataRequest;
import com.raaspal.robotrecommendation.file.dto.FileUploadResponse;
import com.raaspal.robotrecommendation.file.entity.FileUpload;
import com.raaspal.robotrecommendation.file.repository.FileUploadRepository;
import com.raaspal.robotrecommendation.user.entity.User;
import com.raaspal.robotrecommendation.user.service.UserService;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.io.Resource;
import org.springframework.core.io.UrlResource;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.net.MalformedURLException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.UUID;

@Service
@RequiredArgsConstructor
public class FileUploadService {

    private static final Set<String> ALLOWED_EXTENSIONS = Set.of(
            "pdf", "png", "jpg", "jpeg", "xls", "xlsx", "csv"
    );

    private final FileUploadRepository fileUploadRepository;
    private final UserService userService;

    @Value("${app.file.upload-dir:uploads/customer-surveys}")
    private String uploadDir;

    @Transactional(readOnly = true)
    public Page<FileUploadResponse> getAll(Pageable pageable) {
        return fileUploadRepository.findAll(pageable).map(FileUploadResponse::from);
    }

    @Transactional(readOnly = true)
    public FileUpload getEntity(UUID id) {
        return fileUploadRepository.findById(id)
                .orElseThrow(() -> new ResourceNotFoundException("UploadedFile", "id", id));
    }

    @Transactional(readOnly = true)
    public FileUploadResponse getById(UUID id) {
        return FileUploadResponse.from(getEntity(id));
    }

    @Transactional(readOnly = true)
    public List<FileUploadResponse> getByEntity(String entityType, UUID entityId) {
        return fileUploadRepository.findByEntityTypeAndEntityId(entityType, entityId)
                .stream()
                .map(FileUploadResponse::from)
                .toList();
    }

    @Transactional
    public FileUploadResponse registerStoredFile(
            String originalFilename,
            String storedFilename,
            String contentType,
            Long fileSize,
            FileUploadMetadataRequest metadata,
            UUID uploadedById
    ) {
        User uploadedBy = uploadedById == null ? null : userService.getEntity(uploadedById);
        FileUpload fileUpload = FileUpload.builder()
                .originalFilename(originalFilename)
                .storedFilename(storedFilename)
                .contentType(contentType)
                .fileSize(fileSize)
                .entityType(metadata == null ? null : metadata.entityType())
                .entityId(metadata == null ? null : metadata.entityId())
                .uploadedBy(uploadedBy)
                .build();

        return FileUploadResponse.from(fileUploadRepository.save(fileUpload));
    }

    @Transactional
    public FileUploadResponse store(
            MultipartFile multipartFile,
            FileUploadMetadataRequest metadata,
            UUID uploadedById
    ) {
        if (multipartFile == null || multipartFile.isEmpty()) {
            throw new IllegalArgumentException("Uploaded file must not be empty");
        }

        String originalFilename = StringUtils.cleanPath(
                multipartFile.getOriginalFilename() == null ? "upload" : multipartFile.getOriginalFilename()
        );
        if (originalFilename.contains("..")) {
            throw new IllegalArgumentException("Invalid file name");
        }

        String extension = getExtension(originalFilename);
        if (!ALLOWED_EXTENSIONS.contains(extension)) {
            throw new IllegalArgumentException("Unsupported file type. Allowed types: PDF, PNG, JPG, XLS, XLSX, CSV");
        }

        String storedFilename = UUID.randomUUID() + "." + extension;
        Path uploadRoot = getUploadRoot();
        Path destination = uploadRoot.resolve(storedFilename).normalize();
        if (!destination.startsWith(uploadRoot)) {
            throw new IllegalArgumentException("Invalid upload destination");
        }

        try {
            Files.createDirectories(uploadRoot);
            Files.copy(multipartFile.getInputStream(), destination, StandardCopyOption.REPLACE_EXISTING);
        } catch (IOException ex) {
            throw new IllegalStateException("Failed to store uploaded file", ex);
        }

        return registerStoredFile(
                originalFilename,
                storedFilename,
                multipartFile.getContentType(),
                multipartFile.getSize(),
                metadata,
                uploadedById
        );
    }

    @Transactional(readOnly = true)
    public Resource loadAsResource(UUID id) {
        FileUpload fileUpload = getEntity(id);
        Path uploadRoot = getUploadRoot();
        Path filePath = uploadRoot.resolve(fileUpload.getStoredFilename()).normalize();
        if (!filePath.startsWith(uploadRoot)) {
            throw new IllegalArgumentException("Invalid stored file path");
        }

        try {
            Resource resource = new UrlResource(filePath.toUri());
            if (!resource.exists() || !resource.isReadable()) {
                throw new ResourceNotFoundException("Stored file", "id", id);
            }
            return resource;
        } catch (MalformedURLException ex) {
            throw new IllegalStateException("Failed to load stored file", ex);
        }
    }

    @Transactional
    public FileUploadResponse attachToEntity(UUID fileId, FileUploadMetadataRequest metadata) {
        FileUpload fileUpload = getEntity(fileId);
        fileUpload.setEntityType(metadata.entityType());
        fileUpload.setEntityId(metadata.entityId());
        return FileUploadResponse.from(fileUploadRepository.save(fileUpload));
    }

    private Path getUploadRoot() {
        return Path.of(uploadDir).toAbsolutePath().normalize();
    }

    private String getExtension(String filename) {
        int index = filename.lastIndexOf('.');
        if (index < 0 || index == filename.length() - 1) {
            throw new IllegalArgumentException("Uploaded file must have a file extension");
        }
        return filename.substring(index + 1).toLowerCase(Locale.ROOT);
    }
}
