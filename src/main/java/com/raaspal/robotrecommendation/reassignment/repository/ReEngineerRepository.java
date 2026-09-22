package com.raaspal.robotrecommendation.reassignment.repository;

import com.raaspal.robotrecommendation.reassignment.entity.ReEngineer;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Repository
public interface ReEngineerRepository extends JpaRepository<ReEngineer, UUID> {

    List<ReEngineer> findAllByOrderByActiveDescFullNameAsc();

    List<ReEngineer> findByActiveTrue();

    Optional<ReEngineer> findByMondayUserId(String mondayUserId);

    Optional<ReEngineer> findByEmailIgnoreCase(String email);
}
