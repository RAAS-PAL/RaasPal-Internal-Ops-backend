package com.raaspal.robotrecommendation.inventory.repository;

import com.raaspal.robotrecommendation.inventory.entity.StockMovement;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.UUID;

@Repository
public interface StockMovementRepository extends JpaRepository<StockMovement, UUID> {

    /** History for one item, newest first — served by the V28 index on
     *  {@code (inventory_item_id, created_at DESC)}. */
    Page<StockMovement> findByInventoryItemIdOrderByCreatedAtDesc(UUID inventoryItemId, Pageable pageable);

    /** Recent activity across all items — the RIMS dashboard feed. */
    Page<StockMovement> findAllByOrderByCreatedAtDesc(Pageable pageable);

    /**
     * Sum of every movement for an item.
     *
     * <p>Used to verify the cached {@code quantityOnHand} rather than to serve it:
     * if these two ever disagree, something wrote the balance outside the service,
     * and the ledger is the one to believe.
     */
    @Query("SELECT COALESCE(SUM(m.quantityChange), 0) FROM StockMovement m WHERE m.inventoryItemId = :itemId")
    int sumQuantityChange(@Param("itemId") UUID itemId);
}
