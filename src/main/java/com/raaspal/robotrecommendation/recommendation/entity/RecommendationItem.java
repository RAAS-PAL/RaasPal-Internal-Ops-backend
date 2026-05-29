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

    @Column(name = "fit_level", length = 50)
    private String fitLevel;

    @Column(name = "proposal_title", length = 500)
    private String proposalTitle;

    @Column(name = "proposal_summary", columnDefinition = "TEXT")
    private String proposalSummary;

    @Column(name = "why_recommended", columnDefinition = "TEXT")
    private String whyRecommended;

    @Column(name = "matched_requirements", columnDefinition = "TEXT")
    private String matchedRequirements;

    @Column(name = "business_value", columnDefinition = "TEXT")
    private String businessValue;

    @Column(columnDefinition = "TEXT")
    private String limitations;

    @Column(name = "missing_information", columnDefinition = "TEXT")
    private String missingInformation;

    @Column(name = "suggested_next_step", columnDefinition = "TEXT")
    private String suggestedNextStep;

    @CreationTimestamp
    @Column(name = "created_at", nullable = false, updatable = false)
    private LocalDateTime createdAt;
}
