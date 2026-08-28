package com.raaspal.robotrecommendation.inventory.dto;

import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

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

        /**
         * The part number, shown in RIMS simply as "Part number". Holds the
         * manufacturer's code when there is one; left blank, the service issues
         * INV-000001 from a sequence so the column's NOT NULL UNIQUE still holds.
         * <p>
         * There used to be a second field, supplierPartNo, for the manufacturer's
         * code. Two part numbers on one form asked warehouse staff to categorise a
         * number they had simply been handed, so the pair was collapsed into this.
         */
        @Size(max = 64) String sku,

        @Size(max = 64) String barcode,

        @NotBlank @Size(max = 255) String name,

        @NotBlank @Size(max = 64) String category,

        /**
         * The warehouse robots this part fits (robot stock ids). Null or empty
         * means universal. The full set is replaced on every save — the form
         * submits what is ticked, and "what is ticked" is the whole intent.
         */
        List<UUID> robotStockIds,

        @Min(0) Integer reorderPoint,

        @Min(0) Integer reorderQuantity,

        Boolean isActive
) {
}
