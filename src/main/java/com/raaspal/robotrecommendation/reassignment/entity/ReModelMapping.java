package com.raaspal.robotrecommendation.reassignment.entity;

import jakarta.persistence.*;
import lombok.*;

import java.io.Serializable;
import java.time.Instant;

/**
 * A board "Type of Robot" label and the matrix model it counts as. Maps to
 * {@code re_model_mapping} (V53, seeded for the Cleaning board).
 *
 * <p>Only MAPPED labels are auto-suggested. MANUAL (not in the matrix, accessory) and
 * UNCONFIRMED (awaiting the Senior RE) stay manual. A label the board gains later has no
 * row and is treated as MANUAL until someone maps it.
 */
@Entity
@Table(name = "re_model_mapping")
@IdClass(ReModelMapping.Key.class)
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class ReModelMapping {

    public static final String MAPPED = "MAPPED";
    public static final String MANUAL = "MANUAL";
    public static final String UNCONFIRMED = "UNCONFIRMED";

    @Id
    @Column(name = "board_id", columnDefinition = "TEXT")
    private String boardId;

    @Id
    @Column(columnDefinition = "TEXT")
    private String label;

    @Column(nullable = false, columnDefinition = "TEXT")
    private String disposition;

    @Column(name = "skill_code", columnDefinition = "TEXT")
    private String skillCode;

    @Column(columnDefinition = "TEXT")
    private String note;

    @Column(name = "updated_by", nullable = false, columnDefinition = "TEXT")
    private String updatedBy;

    @Column(name = "updated_at", nullable = false)
    @Builder.Default
    private Instant updatedAt = Instant.now();

    @Getter
    @Setter
    @NoArgsConstructor
    @AllArgsConstructor
    @EqualsAndHashCode
    public static class Key implements Serializable {
        private String boardId;
        private String label;
    }
}
