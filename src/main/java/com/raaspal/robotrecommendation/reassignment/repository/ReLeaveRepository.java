package com.raaspal.robotrecommendation.reassignment.repository;

import com.raaspal.robotrecommendation.reassignment.entity.ReLeave;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

@Repository
public interface ReLeaveRepository extends JpaRepository<ReLeave, UUID> {

    List<ReLeave> findByEndsOnGreaterThanEqualOrderByStartsOnAsc(LocalDate day);
}
