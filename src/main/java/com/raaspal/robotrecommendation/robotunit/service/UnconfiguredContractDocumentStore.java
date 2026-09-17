package com.raaspal.robotrecommendation.robotunit.service;

import com.raaspal.robotrecommendation.common.exception.BadRequestException;
import org.springframework.boot.autoconfigure.condition.ConditionalOnExpression;
import org.springframework.stereotype.Service;

/**
 * What a run without a bucket gets: every call explains what is missing. Keeps a
 * local start from failing on a bean the developer has no use for, and turns an
 * attempted upload into one readable sentence rather than an SDK exception.
 */
@Service
@ConditionalOnExpression("'${app.contracts.s3.bucket:}' == ''")
public class UnconfiguredContractDocumentStore implements ContractDocumentStore {

    private static final String MESSAGE =
            "Contract documents are not configured on this server: set CONTRACT_S3_BUCKET, "
                    + "CONTRACT_S3_REGION and the AWS credentials.";

    @Override
    public void put(String key, byte[] bytes, String contentType) {
        throw new BadRequestException(MESSAGE);
    }

    @Override
    public String temporaryUrl(String key, String downloadFileName) {
        throw new BadRequestException(MESSAGE);
    }

    @Override
    public void delete(String key) {
        throw new BadRequestException(MESSAGE);
    }
}
