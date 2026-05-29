package com.raaspal.robotrecommendation.proposal.entity;

import com.raaspal.robotrecommendation.recommendation.entity.Recommendation;
import com.raaspal.robotrecommendation.recommendation.entity.RecommendationItem;
import com.raaspal.robotrecommendation.requirement.entity.Requirement;
import com.raaspal.robotrecommendation.user.entity.User;
import jakarta.persistence.*;
import lombok.*;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.UpdateTimestamp;

import java.time.LocalDateTime;
import java.util.UUID;

@Entity
@Table(name = "generated_proposals")
@Getter
@Setter
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class GeneratedProposal {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "recommendation_id", nullable = false)
    private Recommendation recommendation;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "recommendation_item_id", nullable = false)
    private RecommendationItem recommendationItem;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "requirement_id", nullable = false)
    private Requirement requirement;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "proposal_template_id")
    private ProposalTemplate proposalTemplate;

    @Column(nullable = false, length = 255)
    private String title;

    @Column(name = "proposal_content", nullable = false, columnDefinition = "TEXT")
    private String proposalContent;

    @Builder.Default
    @Column(name = "content_format", nullable = false, length = 50)
    private String contentFormat = "TEXT";

    @Builder.Default
    @Column(nullable = false, length = 20)
    private String status = "GENERATED";

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "generated_by")
    private User generatedBy;

    @CreationTimestamp
    @Column(name = "created_at", nullable = false, updatable = false)
    private LocalDateTime createdAt;

    @UpdateTimestamp
    @Column(name = "updated_at", nullable = false)
    private LocalDateTime updatedAt;
}
