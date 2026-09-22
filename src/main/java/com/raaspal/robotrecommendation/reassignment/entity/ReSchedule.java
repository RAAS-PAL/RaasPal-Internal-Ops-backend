package com.raaspal.robotrecommendation.reassignment.entity;

import jakarta.persistence.*;
import lombok.*;

import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;

/**
 * Days an engineer is booked on a job, so they are not suggested for other work on those
 * days. Maps to {@code re_schedule} (V54). The board keeps only one "RE Action" date per
 * ticket, so a job that runs several days is booked here.
 */
@Entity
@Table(name = "re_schedule")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class ReSchedule {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @Column(name = "engineer_id", nullable = false)
    private UUID engineerId;

    @Column(name = "board_id", columnDefinition = "TEXT")
    private String boardId;

    /** The ticket the booking is for, when there is one. */
    @Column(name = "item_id", columnDefinition = "TEXT")
    private String itemId;

    /** Set when the booking was made with an approval; cancelling the approval removes it. */
    @Column(name = "assignment_id")
    private UUID assignmentId;

    @Column(name = "starts_on", nullable = false)
    private LocalDate startsOn;

    /** Inclusive. */
    @Column(name = "ends_on", nullable = false)
    private LocalDate endsOn;

    @Column(columnDefinition = "TEXT")
    private String note;

    @Column(name = "created_by", nullable = false, columnDefinition = "TEXT")
    private String createdBy;

    @Column(name = "created_at", nullable = false)
    @Builder.Default
    private Instant createdAt = Instant.now();
}
