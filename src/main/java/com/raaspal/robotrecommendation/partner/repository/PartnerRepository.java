package com.raaspal.robotrecommendation.partner.repository;

import com.raaspal.robotrecommendation.partner.entity.Partner;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;
import java.util.UUID;

public interface PartnerRepository extends JpaRepository<Partner, UUID> {

    Optional<Partner> findByNameIgnoreCase(String name);
}
