package com.raaspal.robotrecommendation.reassignment.entity;

import jakarta.persistence.*;
import lombok.*;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

/**
 * One Robot Engineer. Maps to {@code re_engineer} (V53).
 *
 * <p>Added and edited in the console. Never deleted: assignments and skill history point
 * at the row, so someone who leaves is deactivated instead.
 */
@Entity
@Table(name = "re_engineer")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class ReEngineer {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @Column(name = "full_name", nullable = false, columnDefinition = "TEXT")
    private String fullName;

    @Column(columnDefinition = "TEXT")
    private String nickname;

    @Column(columnDefinition = "TEXT")
    private String email;

    /** The engineer's monday account id - what the board's RE (People) column holds. */
    @Column(name = "monday_user_id", unique = true, columnDefinition = "TEXT")
    private String mondayUserId;

    /** Company employee code, e.g. RAAS-00119 (V55). */
    @Column(name = "employee_code", columnDefinition = "TEXT")
    private String employeeCode;

    /** Zone code the engineer is based in (app.re-assignment.zones); null = Bangkok, goes anywhere (V55). */
    @Column(name = "home_zone", columnDefinition = "TEXT")
    private String homeZone;

    @Column(nullable = false)
    @Builder.Default
    private boolean active = true;

    /** Open work allowed before the engineer counts as busy, in load units. */
    @Column(name = "max_load", nullable = false, precision = 5, scale = 2)
    @Builder.Default
    private BigDecimal maxLoad = BigDecimal.valueOf(6);

    @Column(columnDefinition = "TEXT")
    private String note;

    @Column(name = "created_at", nullable = false)
    @Builder.Default
    private Instant createdAt = Instant.now();

    @Column(name = "updated_at", nullable = false)
    @Builder.Default
    private Instant updatedAt = Instant.now();

    /** "Nickname (Full Name)" when there is a nickname, otherwise the full name. */
    public String displayName() {
        return nickname == null || nickname.isBlank() ? fullName : nickname + " (" + fullName + ")";
    }
}
