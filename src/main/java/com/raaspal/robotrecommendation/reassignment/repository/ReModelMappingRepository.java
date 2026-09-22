package com.raaspal.robotrecommendation.reassignment.repository;

import com.raaspal.robotrecommendation.reassignment.entity.ReModelMapping;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
public interface ReModelMappingRepository extends JpaRepository<ReModelMapping, ReModelMapping.Key> {

    List<ReModelMapping> findByBoardIdOrderByDispositionAscLabelAsc(String boardId);
}
