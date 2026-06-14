package com.raaspal.robotrecommendation.telemetry.adapters.gausium.repository;

import com.raaspal.robotrecommendation.telemetry.adapters.gausium.entity.GausiumOAuthToken;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.Optional;
import java.util.UUID;

@Repository
public interface GausiumOAuthTokenRepository extends JpaRepository<GausiumOAuthToken, UUID> {

    Optional<GausiumOAuthToken> findByBrand(String brand);
}