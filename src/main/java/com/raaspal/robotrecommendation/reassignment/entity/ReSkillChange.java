package com.raaspal.robotrecommendation.reassignment.entity;

import jakarta.persistence.*;
import lombok.*;

import java.time.Instant;
import java.util.UUID;

/** One level change, append-only. Maps to {@code re_skill_change} (V53). */
@Entity
@Table(name = "re_skill_change")
@Getter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class ReSkillChange {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "revision_id", nullable = false)
    private UUID revisionId;

    @Column(name = "engineer_id", nullable = false)
    private UUID engineerId;

    @Column(name = "skill_code", nullable = false, columnDefinition = "TEXT")
    private String skillCode;

    @Column(name = "old_level")
    private Short oldLevel;

    @Column(name = "new_level")
    private Short newLevel;

    @Column(name = "changed_at", nullable = false)
    @Builder.Default
    private Instant changedAt = Instant.now();
}
