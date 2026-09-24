package com.raaspal.robotrecommendation.mkstock.repository;

import com.raaspal.robotrecommendation.mkstock.entity.MkAccessPin;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.Optional;
import java.util.UUID;

@Repository
public interface MkAccessPinRepository extends JpaRepository<MkAccessPin, UUID> {

    Optional<MkAccessPin> findByActiveTrue();
}
