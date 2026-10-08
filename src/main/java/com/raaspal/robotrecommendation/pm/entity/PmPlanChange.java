package com.raaspal.robotrecommendation.pm.entity;

import jakarta.persistence.*;
import lombok.*;
import org.springframework.data.domain.Persistable;

import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.UUID;

/**
 * One visit moved from the planner, or one move undone: which, from when, to when,
 * and by whom.
 *
 * <p>Kept here because monday cannot say it. Every change the planner makes on
 * monday is made with one API token, so monday's activity log names the token's
 * owner, never the person who picked the new date.
 *
 * <p>Append-only. An undo is a row of its own pointing at the move it reverses, so
 * the record never loses the move that was undone.
 */
@Entity
@Table(name = "pm_plan_change")
@Getter
@Setter
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class PmPlanChange implements Persistable<UUID> {

    @Id
    private UUID id;

    /** Not a foreign key: the log outlives the visit row it describes. */
    @Column(name = "pm_visit_id", nullable = false)
    private UUID pmVisitId;

    @Column(name = "source_board_id", nullable = false, length = 32)
    private String sourceBoardId;

    @Column(name = "source_item_id", nullable = false, length = 32)
    private String sourceItemId;

    @Enumerated(EnumType.STRING)
    @Column(name = "action", nullable = false, length = 8)
    private Action action;

    /** The move an {@link Action#UNDO} reverses; null on a move. */
    @Column(name = "undoes_change_id")
    private UUID undoesChangeId;

    /**
     * Which date the change moved: the Action date from 2026-10-08 (the planner no longer
     * moves Plan dates), the Plan date for the changes logged before.
     */
    @Enumerated(EnumType.STRING)
    @Column(name = "date_field", nullable = false, length = 8)
    @Builder.Default
    private DateField dateField = DateField.PLAN;

    /** The {@link #dateField}'s date before; null when the visit had none. */
    @Column(name = "old_plan_date")
    private LocalDate oldPlanDate;

    /** The {@link #dateField}'s date after; null only when an undo took it back to none. */
    @Column(name = "new_plan_date")
    private LocalDate newPlanDate;

    /** The visit was completed on monday, and the person confirmed moving it anyway. */
    @Column(name = "confirmed_completed", nullable = false)
    private boolean confirmedCompleted;

    /** The signed-in user's email. */
    @Column(name = "changed_by", nullable = false, columnDefinition = "TEXT")
    private String changedBy;

    @Column(name = "changed_at", nullable = false)
    private OffsetDateTime changedAt;

    /** Assigned ids, so Spring Data cannot tell a new row from an old one by itself. See {@link PmVisit}. */
    @Transient
    @Builder.Default
    private boolean newEntity = true;

    public enum Action {
        MOVE,
        UNDO
    }

    /** A visit's two dates: the one the contract plans, and the one the visit is (or was) done on. */
    public enum DateField {
        PLAN,
        ACTION
    }

    @Override
    public boolean isNew() {
        return newEntity;
    }

    @PostPersist
    @PostLoad
    void markPersisted() {
        this.newEntity = false;
    }
}
