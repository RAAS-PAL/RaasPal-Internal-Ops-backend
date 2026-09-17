package com.raaspal.robotrecommendation.robotunit.dto;

import com.raaspal.robotrecommendation.robotunit.entity.ContractDocument;

import java.time.Instant;
import java.util.UUID;

/**
 * The document attached to a contract row, for the console. Never the storage key:
 * the console asks for a link when somebody clicks, and gets one that expires.
 *
 * @param sharedWith how many deployments point at this document, this one included
 */
public record ContractDocumentInfo(
        UUID id,
        String fileName,
        long sizeBytes,
        String uploadedBy,
        Instant uploadedAt,
        long sharedWith) {

    public static ContractDocumentInfo of(ContractDocument d, long sharedWith) {
        return new ContractDocumentInfo(d.getId(), d.getFileName(), d.getSizeBytes(),
                d.getUploadedBy(), d.getUploadedAt(), sharedWith);
    }
}
