package com.raaspal.robotrecommendation.cvte.entity;

import jakarta.persistence.*;
import lombok.*;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.UpdateTimestamp;

import java.time.LocalDateTime;
import java.util.UUID;

/**
 * A CVTE C3 robot tracked via the Kava Open Gateway API.
 * Status-only for now — see [[index]] "Do Not Implement Yet" for what's deferred
 * (location, map, task tracking, control, alerts, telemetry history).
 */
@Entity
@Table(name = "cvte_devices")
@Getter
@Setter
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class CvteDevice {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @Column(name = "device_id", nullable = false, unique = true)
    private Long deviceId;

    @Column(name = "factory_sn", nullable = false, unique = true, length = 100)
    private String factorySn;

    @Column(name = "device_name", length = 255)
    private String deviceName;

    @Column(name = "org_code", length = 100)
    private String orgCode;

    @Column(name = "online_status")
    private Boolean onlineStatus;

    @Column(name = "running_state", length = 50)
    private String runningState;

    @Column(name = "battery_percentage")
    private Double batteryPercentage;

    @Column(name = "last_checked_at")
    private LocalDateTime lastCheckedAt;

    @Column(name = "last_message", length = 500)
    private String lastMessage;

    @CreationTimestamp
    @Column(name = "created_at", nullable = false, updatable = false)
    private LocalDateTime createdAt;

    @UpdateTimestamp
    @Column(name = "updated_at", nullable = false)
    private LocalDateTime updatedAt;
}
