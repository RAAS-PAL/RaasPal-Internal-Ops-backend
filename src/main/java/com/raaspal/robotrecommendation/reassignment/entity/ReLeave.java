package com.raaspal.robotrecommendation.reassignment.entity;

import jakarta.persistence.*;
import lombok.*;

import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;

/** Leave or a day off: the engineer is unavailable for new work on these dates. Maps to {@code re_leave}. */
@Entity
@Table(name = "re_leave")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class ReLeave {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @Column(name = "engineer_id", nullable = false)
    private UUID engineerId;

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

    public boolean covers(LocalDate day) {
        return !day.isBefore(startsOn) && !day.isAfter(endsOn);
    }
}
