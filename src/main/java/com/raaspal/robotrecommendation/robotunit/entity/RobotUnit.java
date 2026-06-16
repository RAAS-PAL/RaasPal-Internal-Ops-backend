package com.raaspal.robotrecommendation.robotunit.entity;

import jakarta.persistence.*;
import lombok.*;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.UpdateTimestamp;

import java.time.LocalDateTime;
import java.util.UUID;

/**
 * A physical robot unit identified by its manufacturer serial number.
 */
@Entity
@Table(name = "robot_units")
@Getter
@Setter
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class RobotUnit {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @Column(name = "serial_number", nullable = false, unique = true, length = 100)
    private String serialNumber;

    @Column(nullable = false, length = 100)
    private String brand;

    @Column(length = 255)
    private String model;

    @Column(length = 255)
    private String name;

    @CreationTimestamp
    @Column(name = "created_at", nullable = false, updatable = false)
    private LocalDateTime createdAt;

    @UpdateTimestamp
    @Column(name = "updated_at", nullable = false)
    private LocalDateTime updatedAt;
}