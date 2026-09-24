package com.raaspal.robotrecommendation.mkstock.entity;

import jakarta.persistence.*;
import lombok.*;

import java.time.Instant;
import java.util.UUID;

/** An MK part's photo, kept apart from the part so lists stay light. Maps to {@code mk_spare_part_image} (V58). */
@Entity
@Table(name = "mk_spare_part_image")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class MkPartImage {

    @Id
    @Column(name = "part_id")
    private UUID partId;

    /** A base64 {@code data:image/...} URI. */
    @Column(name = "image_data", nullable = false, columnDefinition = "TEXT")
    private String imageData;

    @Column(name = "updated_at", nullable = false)
    @Builder.Default
    private Instant updatedAt = Instant.now();
}
