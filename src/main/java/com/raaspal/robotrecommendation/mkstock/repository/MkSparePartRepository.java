package com.raaspal.robotrecommendation.mkstock.repository;

import com.raaspal.robotrecommendation.mkstock.entity.MkSparePart;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Repository
public interface MkSparePartRepository extends JpaRepository<MkSparePart, UUID> {

    List<MkSparePart> findAllByOrderByActiveDescPartNoAsc();

    Optional<MkSparePart> findByPartNoIgnoreCase(String partNo);

    /** Locks the row, so two movements on one part can never both read the same balance. */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select p from MkSparePart p where p.id = :id")
    Optional<MkSparePart> findForUpdate(@Param("id") UUID id);
}
