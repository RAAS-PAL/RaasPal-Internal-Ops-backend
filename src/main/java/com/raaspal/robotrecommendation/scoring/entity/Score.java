package com.raaspal.robotrecommendation.scoring.entity;

import com.raaspal.robotrecommendation.recommendation.entity.RecommendationItem;
import jakarta.persistence.*;
import lombok.*;
import org.hibernate.annotations.CreationTimestamp;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.UUID;

/**
 * Per-dimension score breakdown for a single recommendation item.
 * One row per scoring dimension (e.g. capability_match, size_access_fit).
 * Provides a full audit trail of how each robot was scored.
 */
@Entity
@Table(name = "scores")
@Getter
@Setter
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class Score {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "recommendation_item_id", nullable = false)
    private RecommendationItem recommendationItem;

    /**
     * Name of the scoring dimension.
     * e.g. capability_match, size_access_fit, coverage_capacity,
     *      floor_suitability, environment_fit, operational_quality
     */
    @Column(nullable = false, length = 100)
    private String dimension;

    /** Raw normalised score for this dimension (0–1). */
    @Column(nullable = false, precision = 8, scale = 4)
    private BigDecimal score;

    /** Configured weight for this dimension (sums to 1.0 across all dimensions). */
    @Column(precision = 5, scale = 4)
    private BigDecimal weight;

    /** score × weight. */
    @Column(name = "weighted_score", precision = 8, scale = 4)
    private BigDecimal weightedScore;

    /** Human-readable note explaining how this dimension score was calculated. */
    @Column(columnDefinition = "TEXT")
    private String notes;

    @CreationTimestamp
    @Column(name = "created_at", nullable = false, updatable = false)
    private LocalDateTime createdAt;
}