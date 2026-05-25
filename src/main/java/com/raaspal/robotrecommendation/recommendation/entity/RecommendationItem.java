package com.raaspal.robotrecommendation.recommendation.entity;

import com.raaspal.robotrecommendation.robot.entity.Robot;
import jakarta.persistence.*;
import lombok.*;
import org.hibernate.annotations.CreationTimestamp;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.UUID;

/**
 * One row per robot ranked within a recommendation session.
 * Per-dimension score breakdown is stored in the {@code scores} table.
 */
@Entity
@Table(name = "recommendation_items")
@Getter
@Setter
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class RecommendationItem {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "recommendation_id", nullable = false)
    private Recommendation recommendation;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "robot_id", nullable = false)
    private Robot robot;

    /** 1-based rank within the session (1 = best match). */
    @Column(name = "rank_position")
    private Integer rankPosition;

    /** Final 0–100 composite score. */
    @Column(name = "total_score", precision = 8, scale = 4)
    private BigDecimal totalScore;

    /** Per-item AI reasoning, complementing the session-level ai_explanation. */
    @Column(name = "ai_reasoning", columnDefinition = "TEXT")
    private String aiReasoning;

    @CreationTimestamp
    @Column(name = "created_at", nullable = false, updatable = false)
    private LocalDateTime createdAt;
}