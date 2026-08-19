package com.raaspal.robotrecommendation.customer.entity;

import com.raaspal.robotrecommendation.user.entity.User;
import jakarta.persistence.*;
import lombok.*;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.UpdateTimestamp;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.UUID;

@Entity
@Table(name = "customer_profiles")
@Getter
@Setter
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class CustomerProfile {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    /**
     * Optional login account. Customers are internal records (report recipients)
     * in this MVP and usually have no account; a future customer login could link
     * one here, so the column stays unique but nullable.
     */
    @OneToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "user_id", unique = true)
    private User user;

    @Column(name = "company_name", nullable = false)
    private String companyName;

    @Column(name = "industry")
    private String industry;

    /** Email the monthly report is delivered to. */
    @Column(name = "contact_email", length = 255)
    private String contactEmail;

    /**
     * When this customer's contract began. Monthly reports clip to it, so a customer
     * who signed mid-month is not shown work done before they were a customer.
     * Null means unknown, which reports the whole month exactly as before.
     */
    @Column(name = "contract_start_date")
    private LocalDate contractStartDate;

    @Column(name = "contact_phone")
    private String contactPhone;

    @Column(name = "branch", columnDefinition = "TEXT")
    private String branch;

    @Column(columnDefinition = "TEXT")
    private String notes;

    /**
     * @deprecated LINE Notify was shut down 2025-03-31. Use {@link #lineUserId}
     * with the LINE Messaging API instead.
     */
    @Deprecated
    @Column(name = "line_notify_token")
    private String lineNotifyToken;

    /**
     * LINE Messaging API push target for monthly report delivery. Holds a user
     * id, group id, or room id interchangeably (LINE's push "to" accepts any),
     * captured by the n8n webhook when the recipient interacts with the
     * RAASPAL Official Account.
     */
    @Column(name = "line_user_id")
    private String lineUserId;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "created_by")
    private User createdBy;

    @CreationTimestamp
    @Column(name = "created_at", nullable = false, updatable = false)
    private LocalDateTime createdAt;

    @UpdateTimestamp
    @Column(name = "updated_at", nullable = false)
    private LocalDateTime updatedAt;
}