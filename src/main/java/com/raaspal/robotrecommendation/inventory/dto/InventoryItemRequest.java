package com.raaspal.robotrecommendation.inventory.dto;

import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;

/**
 * Create or update a stocked part.
 *
 * <p>Note what is absent: {@code quantityOnHand}. Stock levels are never set
 * directly — they are the result of recorded movements. Allowing a quantity here
 * would let an operator overwrite the balance without leaving a reason, which is
 * exactly the hole the ledger exists to close. Use {@code POST /movements} instead.
 */
public record InventoryItemRequest(

        /** Leave blank and one is generated (INV-000001). */
        @Size(max = 64) String sku,

        @Size(max = 64) String supplierPartNo,

        @Size(max = 64) String barcode,

        @NotBlank @Size(max = 255) String name,

        @NotBlank @Size(max = 64) String category,

        /**
         * The warehouse robots this part fits (robot stock ids). Null or empty
         * means universal. The full set is replaced on every save — the form
         * submits what is ticked, and "what is ticked" is the whole intent.
         */
        List<UUID> robotStockIds,

        @Size(max = 16) String unitOfMeasure,

        @Min(0) Integer reorderPoint,

        @Min(0) Integer reorderQuantity,

        BigDecimal unitCost,

        @Size(max = 128) String location,

        Boolean isActive
) {
}
