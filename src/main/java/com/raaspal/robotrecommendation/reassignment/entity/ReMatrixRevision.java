package com.raaspal.robotrecommendation.reassignment.entity;

import jakarta.persistence.*;
import lombok.*;

import java.time.Instant;
import java.util.UUID;

/** One save or import of the skill matrix. Maps to {@code re_matrix_revision} (V53). */
@Entity
@Table(name = "re_matrix_revision")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class ReMatrixRevision {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    /** e.g. "001" for the workbook, "console-2026-09-22-1" for an edit. Unique. */
    @Column(nullable = false, unique = true, columnDefinition = "TEXT")
    private String label;

    /** EXCEL | CONSOLE */
    @Column(nullable = false, columnDefinition = "TEXT")
    private String source;

    @Column(name = "file_hash", columnDefinition = "TEXT")
    private String fileHash;

    @Column(nullable = false, columnDefinition = "TEXT")
    private String reason;

    @Column(name = "created_by", nullable = false, columnDefinition = "TEXT")
    private String createdBy;

    @Column(name = "created_at", nullable = false)
    @Builder.Default
    private Instant createdAt = Instant.now();
}
