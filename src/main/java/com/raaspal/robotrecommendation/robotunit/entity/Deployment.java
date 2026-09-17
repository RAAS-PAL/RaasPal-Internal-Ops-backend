package com.raaspal.robotrecommendation.robotunit.entity;

import com.raaspal.robotrecommendation.customer.entity.CustomerProfile;
import jakarta.persistence.*;
import lombok.*;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.UpdateTimestamp;

import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.UUID;

/**
 * Links a RobotUnit to the CustomerProfile and site where it is installed.
 */
@Entity
@Table(name = "deployments")
@Getter
@Setter
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class Deployment {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "robot_unit_id", nullable = false)
    private RobotUnit robotUnit;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "customer_profile_id", nullable = false)
    private CustomerProfile customerProfile;

    @Column(length = 255)
    private String site;

    /**
     * Distributor / service partner (e.g. PCS) servicing this robot; null =
     * RAASPAL-direct. The partner API scopes all reads through this column.
     * Plain UUID (no relation) to keep the partner module decoupled.
     */
    @Column(name = "partner_id")
    private UUID partnerId;

    @Builder.Default
    @Column(name = "is_active", nullable = false)
    private Boolean isActive = true;

    /** How often this robot's report is sent on a schedule. */
    @Builder.Default
    @Enumerated(EnumType.STRING)
    @Column(name = "report_cadence", nullable = false, length = 20)
    private ReportCadence reportCadence = ReportCadence.MONTHLY;

    /**
     * When this robot started working for this customer under contract.
     * <p>
     * Monthly reports clip to it, so a robot deployed mid-month does not report work
     * it did before the contract began. Null means unknown and reports the whole month.
     * <p>
     * Distinct from {@link #deployedAt}, which is set to now() at registration and so
     * records when the robot was entered into the system, not when its contract started.
     */
    @Column(name = "contract_start_date")
    private LocalDate contractStartDate;

    /**
     * When this robot's contract with this customer ends, inclusive.
     * <p>
     * The final month's report clips to it, and a month that begins after it produces
     * no report: the robot is no longer the customer's. Null means no end is known and
     * reports as before. Never before {@link #contractStartDate}; the service refuses that.
     */
    @Column(name = "contract_end_date")
    private LocalDate contractEndDate;

    /**
     * When the "contract ends soon" alert went out for this end date; null = not yet.
     * Cleared whenever the end date changes, so an extended contract is alerted again
     * as its new end approaches.
     */
    @Column(name = "contract_expiry_alerted_at")
    private Instant contractExpiryAlertedAt;

    /**
     * The customer success follow-up on this contract term: whether the customer has
     * been called about renewing, and what they said. Null = not contacted yet.
     * Cleared with {@link #contractExpiryAlertedAt} whenever the end date changes, so
     * a renewed contract is chased afresh for its next term.
     */
    @Enumerated(EnumType.STRING)
    @Column(name = "renewal_status", length = 20)
    private ContractRenewalStatus renewalStatus;

    @Column(name = "renewal_note", columnDefinition = "TEXT")
    private String renewalNote;

    @Column(name = "renewal_updated_by")
    private String renewalUpdatedBy;

    @Column(name = "renewal_updated_at")
    private Instant renewalUpdatedAt;

    /** Back to "not contacted", for a new term. */
    public void clearRenewalFollowup() {
        this.renewalStatus = null;
        this.renewalNote = null;
        this.renewalUpdatedBy = null;
        this.renewalUpdatedAt = null;
    }

    /**
     * The signed contract PDF covering this deployment, if one has been attached.
     * Shared with every other deployment the same contract covers; see
     * {@link ContractDocument}.
     */
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "contract_document_id")
    private ContractDocument contractDocument;

    @Column(name = "deployed_at", nullable = false)
    private LocalDateTime deployedAt;

    @CreationTimestamp
    @Column(name = "created_at", nullable = false, updatable = false)
    private LocalDateTime createdAt;

    @UpdateTimestamp
    @Column(name = "updated_at", nullable = false)
    private LocalDateTime updatedAt;
}