package com.raaspal.robotrecommendation.reassignment.entity;

import jakarta.persistence.*;
import lombok.*;

/** One skill column of the RE skill matrix. Maps to {@code re_skill_definition} (V53, seeded). */
@Entity
@Table(name = "re_skill_definition")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class ReSkillDefinition {

    /** Stable code, e.g. CM_CLEANING, PHANTAS. Never reused for a different skill. */
    @Id
    @Column(columnDefinition = "TEXT")
    private String code;

    /** SOFT | OVERALL | INSTALLATION | PM | CM | EXPERTISE | MODEL */
    @Column(name = "group_code", nullable = false, columnDefinition = "TEXT")
    private String groupCode;

    /** CLEANING | DELIVERY | COMMON */
    @Column(name = "board_type", nullable = false, columnDefinition = "TEXT")
    private String boardType;

    @Column(nullable = false, columnDefinition = "TEXT")
    private String label;

    @Column(nullable = false, unique = true)
    private int ordinal;
}
