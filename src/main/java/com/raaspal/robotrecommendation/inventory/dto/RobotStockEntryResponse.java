package com.raaspal.robotrecommendation.inventory.dto;

import com.raaspal.robotrecommendation.common.enums.RobotType;
import com.raaspal.robotrecommendation.inventory.entity.RobotStockEntry;
import com.raaspal.robotrecommendation.robotunit.entity.RobotUnitStatus;

import java.time.LocalDateTime;
import java.util.UUID;

/** A robot the warehouse holds. */
public record RobotStockEntryResponse(
        UUID id,
        RobotType robotType,
        String brand,
        String model,
        String version,
        String imageUrl,
        Integer quantity,

        /** What it was before the last change to the count. One step back, not a history. */
        Integer previousQuantity,
        LocalDateTime previousQuantityAt,

        RobotUnitStatus status,
        String location,
        String note,

        /** "Gausium Phantas v1.3" — assembled once here so every screen agrees. */
        String displayName,

        LocalDateTime updatedAt
) {

    public static RobotStockEntryResponse from(RobotStockEntry e) {
        String display = (e.getBrand() + " " + e.getModel()
                + (e.getVersion() == null || e.getVersion().isBlank() ? "" : " " + e.getVersion())).trim();

        return new RobotStockEntryResponse(
                e.getId(),
                e.getRobotType(),
                e.getBrand(),
                e.getModel(),
                e.getVersion(),
                e.getImageUrl(),
                e.getQuantity(),
                e.getPreviousQuantity(),
                e.getPreviousQuantityAt(),
                e.getStatus(),
                e.getLocation(),
                e.getNote(),
                display,
                e.getUpdatedAt());
    }
}
