package com.raaspal.robotrecommendation.robotunit.service;

import com.raaspal.robotrecommendation.common.exception.BadRequestException;
import com.raaspal.robotrecommendation.common.exception.ResourceNotFoundException;
import com.raaspal.robotrecommendation.robotunit.dto.ContractDocumentInfo;
import com.raaspal.robotrecommendation.robotunit.entity.ContractDocument;
import com.raaspal.robotrecommendation.robotunit.entity.Deployment;
import com.raaspal.robotrecommendation.robotunit.repository.ContractDocumentRepository;
import com.raaspal.robotrecommendation.robotunit.repository.DeploymentRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * Attaching, opening, replacing and removing the contract PDF behind a deployment.
 *
 * <p>Addressed by robot unit, because that is what a Contracts row is: the active
 * deployment of that robot. One PDF can cover several robots - the staff say so at
 * upload time, and every other active deployment of the same customer with the same
 * start and end dates is pointed at the same document.
 *
 * <p>The bucket write happens before the database write, so a failed upload leaves
 * at worst an unreferenced object in the bucket and never a row that points at
 * nothing. A document nothing points at any more is deleted from both.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ContractDocumentService {

    /** PDF only. A contract is a signed document, and the reader expects one file type. */
    public static final long MAX_BYTES = 20L * 1024 * 1024;
    private static final byte[] PDF_MAGIC = "%PDF-".getBytes(StandardCharsets.US_ASCII);

    private final DeploymentRepository deployments;
    private final ContractDocumentRepository documents;
    private final ContractDocumentStore store;

    /** What an attach did: the document, and how many deployments now point at it. */
    public record Attached(ContractDocumentInfo document, int deploymentsLinked) {
    }

    /**
     * @param applyToSameContract also point every other active deployment of the same
     *                            customer with the same contract dates at this PDF
     */
    @Transactional
    public Attached attach(UUID robotUnitId,
                           String fileName,
                           String contentType,
                           byte[] bytes,
                           boolean applyToSameContract,
                           String uploadedBy) {
        Deployment target = activeDeployment(robotUnitId);
        validatePdf(fileName, bytes);

        String safeName = fileName == null || fileName.isBlank() ? "contract.pdf" : fileName.strip();
        String key = "contracts/" + target.getCustomerProfile().getId() + "/" + UUID.randomUUID() + ".pdf";
        store.put(key, bytes, "application/pdf");

        ContractDocument document = documents.save(ContractDocument.builder()
                .customerProfile(target.getCustomerProfile())
                .fileName(safeName)
                .contentType("application/pdf")
                .sizeBytes(bytes.length)
                .storageKey(key)
                .uploadedBy(uploadedBy)
                .uploadedAt(Instant.now())
                .build());

        List<Deployment> covered = new ArrayList<>();
        covered.add(target);
        if (applyToSameContract && target.getContractEndDate() != null) {
            for (Deployment d : deployments.findActiveOnSameContract(
                    target.getCustomerProfile().getId(), target.getContractStartDate(), target.getContractEndDate())) {
                if (!d.getId().equals(target.getId())) covered.add(d);
            }
        }

        List<ContractDocument> orphaned = new ArrayList<>();
        for (Deployment d : covered) {
            ContractDocument previous = d.getContractDocument();
            d.setContractDocument(document);
            if (previous != null && !orphaned.contains(previous)) orphaned.add(previous);
        }
        deployments.saveAll(covered);
        deployments.flush();
        orphaned.forEach(this::deleteIfUnreferenced);

        log.info("Attached contract document {} ({} bytes) to {} deployment(s) of customer {} by {}",
                safeName, bytes.length, covered.size(), target.getCustomerProfile().getId(), uploadedBy);
        return new Attached(ContractDocumentInfo.of(document, covered.size()), covered.size());
    }

    /** A link to the PDF that works for a few minutes. */
    @Transactional(readOnly = true)
    public String temporaryUrl(UUID robotUnitId) {
        Deployment d = activeDeployment(robotUnitId);
        if (d.getContractDocument() == null) {
            throw new ResourceNotFoundException("No contract document is attached to this robot.");
        }
        return store.temporaryUrl(d.getContractDocument().getStorageKey(), d.getContractDocument().getFileName());
    }

    /**
     * Detaches the PDF from this robot only. The other robots on the same contract
     * keep it; the file itself goes only when the last of them lets go.
     */
    @Transactional
    public void remove(UUID robotUnitId) {
        Deployment d = activeDeployment(robotUnitId);
        ContractDocument document = d.getContractDocument();
        if (document == null) return;
        d.setContractDocument(null);
        deployments.saveAndFlush(d);
        deleteIfUnreferenced(document);
        log.info("Removed contract document {} from robot {}", document.getFileName(), robotUnitId);
    }

    private void deleteIfUnreferenced(ContractDocument document) {
        if (deployments.countByContractDocumentId(document.getId()) > 0) return;
        documents.delete(document);
        try {
            store.delete(document.getStorageKey());
        } catch (RuntimeException e) {
            // The row is gone, which is what the reader sees; an orphaned object in
            // the bucket costs a fraction of a cent and is logged for a cleanup.
            log.warn("Contract document row {} deleted but its object {} was not: {}",
                    document.getId(), document.getStorageKey(), e.getMessage());
        }
    }

    private Deployment activeDeployment(UUID robotUnitId) {
        List<Deployment> active = deployments.findByRobotUnitIdAndIsActiveTrue(robotUnitId);
        if (active.isEmpty()) {
            throw new ResourceNotFoundException("Robot " + robotUnitId + " has no active deployment.");
        }
        return active.get(0);
    }

    private static void validatePdf(String fileName, byte[] bytes) {
        if (bytes == null || bytes.length == 0) {
            throw new BadRequestException("The file is empty.");
        }
        if (bytes.length > MAX_BYTES) {
            throw new BadRequestException("The file is larger than 20 MB.");
        }
        // The bytes, not the extension or the browser's content type: both are whatever
        // the sender says, and a renamed .docx would otherwise be stored as a contract.
        if (bytes.length < PDF_MAGIC.length) {
            throw new BadRequestException("The file is not a PDF.");
        }
        for (int i = 0; i < PDF_MAGIC.length; i++) {
            if (bytes[i] != PDF_MAGIC[i]) {
                throw new BadRequestException("The file is not a PDF"
                        + (fileName == null ? "." : " (" + fileName + ")."));
            }
        }
    }

    /** For the controller: the multipart's bytes, or a readable refusal. */
    public static byte[] bytesOf(org.springframework.web.multipart.MultipartFile file) {
        try {
            return file.getBytes();
        } catch (IOException e) {
            throw new BadRequestException("The file could not be read: " + e.getMessage());
        }
    }
}
