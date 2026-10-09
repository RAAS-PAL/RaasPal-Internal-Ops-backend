package com.raaspal.robotrecommendation.inventory.repository;

import com.raaspal.robotrecommendation.inventory.dto.RobotStockEntryResponse;
import com.raaspal.robotrecommendation.inventory.entity.RobotStockEntry;
import com.raaspal.robotrecommendation.robotunit.entity.RobotUnitStatus;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Modifying;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Repository
public interface RobotStockEntryRepository extends JpaRepository<RobotStockEntry, UUID> {

    /*
     * Search is two methods rather than one with optional parameters.
     *
     * The single-query version used the familiar `(:param IS NULL OR ...)` trick and
     * failed at runtime: with a null keyword, Postgres has nothing to infer the
     * parameter's type from, defaults it to `bytea`, and then `lower(bytea)` does not
     * exist — a 500 that no amount of compiling or typechecking would have caught.
     *
     * The pattern is built by the caller ("%phantas%", or "%%" for everything) so no
     * parameter is ever null and Postgres always knows what it is holding. Two plain
     * methods are also easier to read than one query with two escape hatches in it.
     */

    /*
     * The list queries return the response directly and never select image_url. That
     * column holds each robot's photo as a base64 data URI (~40 KB each, ~3.7 MB for
     * the whole table on 2026-09-28), and loading whole rows pulled all of it from the
     * database in Sydney on every list call — which RIMS makes on every page, for the
     * sidebar counts. hasImage is worked out in the database instead. LENGTH rather
     * than a comparison with '': H2, which the tests run on, cannot compare a TEXT
     * (CLOB) column.
     */

    /** Everything held, filtered by a lowercase LIKE pattern. Pass "%%" for all. */
    @Query("""
           SELECT new com.raaspal.robotrecommendation.inventory.dto.RobotStockEntryResponse(
                  e.id, e.robotType, e.brand, e.model, e.version,
                  CASE WHEN LENGTH(e.imageUrl) > 0 THEN true ELSE false END,
                  e.quantity, e.previousQuantity, e.previousQuantityAt, e.status, e.packaging,
                  e.location, e.note, e.updatedAt)
           FROM RobotStockEntry e
           WHERE LOWER(e.brand) LIKE :pattern
              OR LOWER(e.model) LIKE :pattern
              OR LOWER(COALESCE(e.version, '')) LIKE :pattern
           ORDER BY e.brand ASC, e.model ASC, e.version ASC
           """)
    List<RobotStockEntryResponse> search(@Param("pattern") String pattern);

    /** The same, narrowed to one status. */
    @Query("""
           SELECT new com.raaspal.robotrecommendation.inventory.dto.RobotStockEntryResponse(
                  e.id, e.robotType, e.brand, e.model, e.version,
                  CASE WHEN LENGTH(e.imageUrl) > 0 THEN true ELSE false END,
                  e.quantity, e.previousQuantity, e.previousQuantityAt, e.status, e.packaging,
                  e.location, e.note, e.updatedAt)
           FROM RobotStockEntry e
           WHERE e.status = :status
             AND (LOWER(e.brand) LIKE :pattern
                  OR LOWER(e.model) LIKE :pattern
                  OR LOWER(COALESCE(e.version, '')) LIKE :pattern)
           ORDER BY e.brand ASC, e.model ASC, e.version ASC
           """)
    List<RobotStockEntryResponse> searchByStatus(@Param("pattern") String pattern,
                                                 @Param("status") RobotUnitStatus status);

    /** The same rows for a set of ids, for naming the robots a part is linked to. */
    @Query("""
           SELECT new com.raaspal.robotrecommendation.inventory.dto.RobotStockEntryResponse(
                  e.id, e.robotType, e.brand, e.model, e.version,
                  CASE WHEN LENGTH(e.imageUrl) > 0 THEN true ELSE false END,
                  e.quantity, e.previousQuantity, e.previousQuantityAt, e.status, e.packaging,
                  e.location, e.note, e.updatedAt)
           FROM RobotStockEntry e
           WHERE e.id IN :ids
           """)
    List<RobotStockEntryResponse> findRowsByIdIn(@Param("ids") Collection<UUID> ids);

    /** Which of these ids exist — an existence check that does not load any row. */
    @Query("SELECT e.id FROM RobotStockEntry e WHERE e.id IN :ids")
    List<UUID> findExistingIds(@Param("ids") Collection<UUID> ids);

    /**
     * The row for one robot in one status, used to catch a duplicate before the
     * unique index does — a named error beats a constraint violation.
     */
    @Query("""
           SELECT e FROM RobotStockEntry e
           WHERE LOWER(TRIM(e.brand)) = LOWER(TRIM(:brand))
             AND LOWER(TRIM(e.model)) = LOWER(TRIM(:model))
             AND LOWER(COALESCE(e.version, '')) = LOWER(COALESCE(:version, ''))
             AND e.status = :status
           """)
    Optional<RobotStockEntry> findIdentity(@Param("brand") String brand,
                                           @Param("model") String model,
                                           @Param("version") String version,
                                           @Param("status") RobotUnitStatus status);

    /** Model identity deliberately excludes status; null and empty versions agree. */
    @Query("""
           SELECT e FROM RobotStockEntry e
           WHERE LOWER(TRIM(e.brand)) = LOWER(TRIM(:brand))
             AND LOWER(TRIM(e.model)) = LOWER(TRIM(:model))
             AND LOWER(COALESCE(e.version, '')) = LOWER(:version)
           ORDER BY e.id
           """)
    List<RobotStockEntry> findModel(@Param("brand") String brand, @Param("model") String model,
                                   @Param("version") String version);

    // Lock in one order even when two operators move units in opposite directions.
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("""
           SELECT e FROM RobotStockEntry e
           WHERE LOWER(TRIM(e.brand)) = LOWER(TRIM(:brand))
             AND LOWER(TRIM(e.model)) = LOWER(TRIM(:model))
             AND LOWER(COALESCE(e.version, '')) = LOWER(:version)
           ORDER BY e.id
           """)
    List<RobotStockEntry> lockModel(@Param("brand") String brand, @Param("model") String model,
                                   @Param("version") String version);

    // Insert only the new links, rather than rewriting each part's whole collection
    // and risking removal of links added by another stock operation.
    @Modifying
    @Query(value = """
            INSERT INTO inventory_item_robots (inventory_item_id, robot_stock_id)
            SELECT inventory_item_id, :targetId FROM inventory_item_robots WHERE robot_stock_id = :sourceId
            """, nativeQuery = true)
    void copyPartLinks(@Param("sourceId") UUID sourceId, @Param("targetId") UUID targetId);

    /**
     * Unlinks parts from these rows. Run before deleting a whole model, so the parts
     * stay recorded but unlinked whether or not the database enforces the cascade.
     */
    @Modifying
    @Query(value = "DELETE FROM inventory_item_robots WHERE robot_stock_id IN (:ids)", nativeQuery = true)
    void deletePartLinks(@Param("ids") Collection<UUID> ids);

    /** Total robots held, by status — the dashboard tiles. */
    @Query("SELECT COALESCE(SUM(e.quantity), 0) FROM RobotStockEntry e WHERE e.status = :status")
    long sumQuantityByStatus(@Param("status") RobotUnitStatus status);
}
