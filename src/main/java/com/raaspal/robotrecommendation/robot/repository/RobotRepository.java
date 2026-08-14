package com.raaspal.robotrecommendation.robot.repository;

import com.raaspal.robotrecommendation.robot.entity.Robot;
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
public interface RobotRepository extends JpaRepository<Robot, UUID> {

    Page<Robot> findAllByBrandIgnoreCase(String brand, Pageable pageable);

    Optional<Robot> findByBrandIgnoreCaseAndModelIgnoreCase(String brand, String model);

    @Query("SELECT r FROM Robot r WHERE " +
            "LOWER(r.model) LIKE LOWER(CONCAT('%', :keyword, '%')) OR " +
            "LOWER(r.brand) LIKE LOWER(CONCAT('%', :keyword, '%'))")
    Page<Robot> search(@Param("keyword") String keyword, Pageable pageable);

    /**
     * Every cleaning model that has specs, with the whole spec row as JSON.
     *
     * <p>Native and untyped on purpose — see {@code RobotSpecMatrixRow}. The
     * bookkeeping columns are stripped here rather than in Java so the payload
     * carries only real specifications.
     *
     * <p>An INNER JOIN, so models without a spec row are absent rather than
     * present-and-empty: a column of blanks in the matrix would read as "this
     * robot has no features" instead of "nobody has filled this in yet".
     */
    @Query(value = """
            SELECT r.id                AS robot_id,
                   r.brand             AS brand,
                   r.model             AS model,
                   r.test_status       AS test_status,
                   (to_jsonb(s) - 'id' - 'robot_id' - 'created_at' - 'updated_at')::text AS specs
            FROM robots r
            JOIN robot_specs_cleaning s ON s.robot_id = r.id
            ORDER BY r.brand, r.model
            """, nativeQuery = true)
    List<Object[]> findCleaningSpecMatrix();
}
