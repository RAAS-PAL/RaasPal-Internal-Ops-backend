package com.raaspal.robotrecommendation.inventory.repository;

import com.raaspal.robotrecommendation.inventory.entity.InventoryItem;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Repository
public interface InventoryItemRepository extends JpaRepository<InventoryItem, UUID> {

    Optional<InventoryItem> findBySkuIgnoreCase(String sku);

    boolean existsBySkuIgnoreCase(String sku);

    /**
     * The main list. Every filter is optional and skipped when null, so one query
     * serves "everything", a search, a category tab, and the low-stock view rather
     * than four near-identical methods drifting apart.
     *
     * <p>{@code lowStock} compares the cached balance against each item's own
     * threshold, so a costly battery can alert at 2 while brushes alert at 10.
     */
    /*
     * CAST(:param AS string) is not decoration — without it this query throws.
     *
     * When a nullable parameter is passed to LOWER(), Postgres has nothing to infer
     * its type from, defaults to `bytea`, and fails with "function lower(bytea) does
     * not exist". The cast tells it what it is holding. CvteDeviceRepository already
     * does this for the same reason; it is the house pattern for an optional text
     * filter and worth copying rather than rediscovering.
     */
    @Query("""
           SELECT i FROM InventoryItem i
           WHERE (:activeOnly = false OR i.isActive = true)
             AND (:category IS NULL OR LOWER(i.category) = LOWER(CAST(:category AS string)))
             AND (:robotId IS NULL OR i.robotId = :robotId)
             AND (:lowStock = false OR i.quantityOnHand <= i.reorderPoint)
             AND (:keyword IS NULL
                  OR LOWER(i.name) LIKE LOWER(CONCAT('%', CAST(:keyword AS string), '%'))
                  OR LOWER(i.sku)  LIKE LOWER(CONCAT('%', CAST(:keyword AS string), '%'))
                  OR LOWER(COALESCE(i.supplierPartNo, '')) LIKE LOWER(CONCAT('%', CAST(:keyword AS string), '%')))
           """)
    Page<InventoryItem> search(@Param("keyword") String keyword,
                               @Param("category") String category,
                               @Param("robotId") UUID robotId,
                               @Param("lowStock") boolean lowStock,
                               @Param("activeOnly") boolean activeOnly,
                               Pageable pageable);

    /** Items at or below their reorder point — the dashboard alert list. */
    @Query("""
           SELECT i FROM InventoryItem i
           WHERE i.isActive = true AND i.quantityOnHand <= i.reorderPoint
           ORDER BY (i.quantityOnHand - i.reorderPoint) ASC, i.name ASC
           """)
    List<InventoryItem> findLowStock();

    @Query("SELECT COUNT(i) FROM InventoryItem i WHERE i.isActive = true AND i.quantityOnHand <= i.reorderPoint")
    long countLowStock();

    @Query("SELECT COUNT(i) FROM InventoryItem i WHERE i.isActive = true")
    long countActive();

    /** Distinct categories in use, for the filter control. Derived rather than
     *  configured, so a new category appears the moment an item uses it. */
    @Query("SELECT DISTINCT i.category FROM InventoryItem i WHERE i.isActive = true ORDER BY i.category")
    List<String> findCategories();

    /** Total value of stock on hand. Null when no item has a unit cost. */
    @Query("SELECT SUM(i.unitCost * i.quantityOnHand) FROM InventoryItem i WHERE i.isActive = true AND i.unitCost IS NOT NULL")
    java.math.BigDecimal sumStockValue();

    /** Next value of the SKU sequence created in V28. */
    @Query(value = "SELECT nextval('inventory_item_sku_seq')", nativeQuery = true)
    Long nextSkuNumber();
}
