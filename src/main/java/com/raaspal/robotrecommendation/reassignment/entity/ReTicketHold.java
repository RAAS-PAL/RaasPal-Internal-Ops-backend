package com.raaspal.robotrecommendation.reassignment.entity;

import jakarta.persistence.*;
import lombok.*;

import java.time.Instant;
import java.util.UUID;

/**
 * A ticket the Senior RE took out of auto-suggestion ("reject"). While active (not
 * released) the queue shows the ticket as held and suggests nobody. Maps to {@code re_ticket_hold}.
 */
@Entity
@Table(name = "re_ticket_hold")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class ReTicketHold {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @Column(name = "board_id", nullable = false, columnDefinition = "TEXT")
    private String boardId;

    @Column(name = "item_id", nullable = false, columnDefinition = "TEXT")
    private String itemId;

    @Column(nullable = false, columnDefinition = "TEXT")
    private String reason;

    @Column(name = "held_by", nullable = false, columnDefinition = "TEXT")
    private String heldBy;

    @Column(name = "held_at", nullable = false)
    @Builder.Default
    private Instant heldAt = Instant.now();

    @Column(name = "released_by", columnDefinition = "TEXT")
    private String releasedBy;

    @Column(name = "released_at")
    private Instant releasedAt;
}
