package com.raaspal.robotrecommendation.reassignment.repository;

import com.raaspal.robotrecommendation.reassignment.entity.ReSchedule;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

@Repository
public interface ReScheduleRepository extends JpaRepository<ReSchedule, UUID> {

    List<ReSchedule> findByEndsOnGreaterThanEqualOrderByStartsOnAsc(LocalDate day);

    List<ReSchedule> findByAssignmentId(UUID assignmentId);
}
