package com.raaspal.robotrecommendation.inventory.dto;

import com.raaspal.robotrecommendation.common.enums.RobotType;
import com.raaspal.robotrecommendation.inventory.entity.Packaging;
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
        /**
         * Whether a photo exists, rather than the photo itself.
         * <p>
         * This used to carry the full base64 data URI. The layout reads the robot
         * list on every page to count robots for the sidebar, so every page in RIMS
         * was downloading 3.8 MB of photographs to compute a number and then
         * discarding them. The bytes now come from
         * {@code GET /inventory/robot-stock/{id}/image}.
         */
        boolean hasImage,
        Integer quantity,

        /** What it was before the last change to the count. One step back, not a history. */
        Integer previousQuantity,
        LocalDateTime previousQuantityAt,

        RobotUnitStatus status,

        /** BOX, UNBOX, or null where nobody has recorded it. */
        Packaging packaging,

        String location,
        String note,

        /** "Gausium Phantas v1.3" — assembled once here so every screen agrees. */
        String displayName,

        LocalDateTime updatedAt
) {

    /**
     * For the repository's {@code SELECT new} list queries, which never load the photo
     * column. {@code hasImage} is computed in the database; the display name is
     * assembled here exactly as the entity assembles it.
     *
     * <p>The earlier fix above stopped the photos reaching the browser, but the list
     * still read every row whole, so each call pulled the ~3.7 MB of base64 photos
     * from the database in Sydney to the API in Singapore and dropped them — on every
     * RIMS page, because the layout lists robots for the sidebar.
     */
    public RobotStockEntryResponse(UUID id, RobotType robotType, String brand, String model, String version,
                                   boolean hasImage, Integer quantity, Integer previousQuantity,
                                   LocalDateTime previousQuantityAt, RobotUnitStatus status, Packaging packaging,
                                   String location, String note, LocalDateTime updatedAt) {
        this(id, robotType, brand, model, version, hasImage, quantity, previousQuantity, previousQuantityAt,
                status, packaging, location, note, RobotStockEntry.displayName(brand, model, version), updatedAt);
    }

    public static RobotStockEntryResponse from(RobotStockEntry e) {
        String display = e.displayName();

        return new RobotStockEntryResponse(
                e.getId(),
                e.getRobotType(),
                e.getBrand(),
                e.getModel(),
                e.getVersion(),
                e.getImageUrl() != null && !e.getImageUrl().isBlank(),
                e.getQuantity(),
                e.getPreviousQuantity(),
                e.getPreviousQuantityAt(),
                e.getStatus(),
                e.getPackaging(),
                e.getLocation(),
                e.getNote(),
                display,
                e.getUpdatedAt());
    }
}
