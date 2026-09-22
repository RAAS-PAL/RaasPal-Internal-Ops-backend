package com.raaspal.robotrecommendation.reassignment.repository;

import com.raaspal.robotrecommendation.reassignment.entity.ReEvent;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
public interface ReEventRepository extends JpaRepository<ReEvent, Long> {

    List<ReEvent> findTop100ByOrderByIdDesc();
}
