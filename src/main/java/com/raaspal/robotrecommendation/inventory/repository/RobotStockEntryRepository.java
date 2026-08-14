package com.raaspal.robotrecommendation.inventory.repository;

import com.raaspal.robotrecommendation.inventory.entity.RobotStockEntry;
import com.raaspal.robotrecommendation.robotunit.entity.RobotUnitStatus;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

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

    /** Everything held, filtered by a lowercase LIKE pattern. Pass "%%" for all. */
    @Query("""
           SELECT e FROM RobotStockEntry e
           WHERE LOWER(e.brand) LIKE :pattern
              OR LOWER(e.model) LIKE :pattern
              OR LOWER(COALESCE(e.version, '')) LIKE :pattern
           ORDER BY e.brand ASC, e.model ASC, e.version ASC
           """)
    List<RobotStockEntry> search(@Param("pattern") String pattern);

    /** The same, narrowed to one status. */
    @Query("""
           SELECT e FROM RobotStockEntry e
           WHERE e.status = :status
             AND (LOWER(e.brand) LIKE :pattern
                  OR LOWER(e.model) LIKE :pattern
                  OR LOWER(COALESCE(e.version, '')) LIKE :pattern)
           ORDER BY e.brand ASC, e.model ASC, e.version ASC
           """)
    List<RobotStockEntry> searchByStatus(@Param("pattern") String pattern,
                                         @Param("status") RobotUnitStatus status);

    /**
     * The row for one robot in one status, used to catch a duplicate before the
     * unique index does — a named error beats a constraint violation.
     */
    @Query("""
           SELECT e FROM RobotStockEntry e
           WHERE LOWER(e.brand) = LOWER(:brand)
             AND LOWER(e.model) = LOWER(:model)
             AND LOWER(COALESCE(e.version, '')) = LOWER(COALESCE(:version, ''))
             AND e.status = :status
           """)
    Optional<RobotStockEntry> findIdentity(@Param("brand") String brand,
                                           @Param("model") String model,
                                           @Param("version") String version,
                                           @Param("status") RobotUnitStatus status);

    /** Total robots held, by status — the dashboard tiles. */
    @Query("SELECT COALESCE(SUM(e.quantity), 0) FROM RobotStockEntry e WHERE e.status = :status")
    long sumQuantityByStatus(@Param("status") RobotUnitStatus status);
}
