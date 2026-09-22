package com.raaspal.robotrecommendation.reassignment.entity;

import jakarta.persistence.*;
import lombok.*;

import java.io.Serializable;
import java.time.Instant;
import java.util.UUID;

/**
 * One engineer's current level in one skill. Maps to {@code re_skill_level} (V53).
 *
 * <p>{@code level} 1-4 = L1-L4; null = "-" / not assessed, which never qualifies.
 * Every change is also written to {@link ReSkillChange} under a {@link ReMatrixRevision}.
 */
@Entity
@Table(name = "re_skill_level")
@IdClass(ReSkillLevel.Key.class)
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class ReSkillLevel {

    @Id
    @Column(name = "engineer_id")
    private UUID engineerId;

    @Id
    @Column(name = "skill_code", columnDefinition = "TEXT")
    private String skillCode;

    @Column(name = "level_value")
    private Short level;

    @Column(name = "revision_id", nullable = false)
    private UUID revisionId;

    @Column(name = "updated_at", nullable = false)
    @Builder.Default
    private Instant updatedAt = Instant.now();

    @Getter
    @Setter
    @NoArgsConstructor
    @AllArgsConstructor
    @EqualsAndHashCode
    public static class Key implements Serializable {
        private UUID engineerId;
        private String skillCode;
    }
}
