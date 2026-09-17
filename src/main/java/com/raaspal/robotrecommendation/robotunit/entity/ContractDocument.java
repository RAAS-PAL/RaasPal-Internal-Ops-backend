package com.raaspal.robotrecommendation.robotunit.entity;

import com.raaspal.robotrecommendation.customer.entity.CustomerProfile;
import jakarta.persistence.*;
import lombok.*;

import java.time.Instant;
import java.util.UUID;

/**
 * A signed contract PDF, attached from the Contracts page.
 *
 * <p>The bytes are in S3; this row is the name, the size, who attached it and when,
 * and the key the bucket knows it by. Shared: a customer's one contract usually
 * covers several robots, and each of their {@link Deployment}s points here.
 */
@Entity
@Table(name = "contract_documents")
@Getter
@Setter
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ContractDocument {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "customer_profile_id", nullable = false)
    private CustomerProfile customerProfile;

    @Column(name = "file_name", nullable = false, length = 255)
    private String fileName;

    @Column(name = "content_type", nullable = false, length = 100)
    private String contentType;

    @Column(name = "size_bytes", nullable = false)
    private long sizeBytes;

    /** Where the bytes are in the bucket. Never shown; the store turns it into a link. */
    @Column(name = "storage_key", nullable = false, unique = true, length = 512)
    private String storageKey;

    @Column(name = "uploaded_by", length = 255)
    private String uploadedBy;

    @Column(name = "uploaded_at", nullable = false)
    private Instant uploadedAt;
}
