package com.raaspal.robotrecommendation.customer.repository;

import com.raaspal.robotrecommendation.customer.entity.CustomerProfile;
import com.raaspal.robotrecommendation.user.entity.User;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.Optional;
import java.util.UUID;

@Repository
public interface CustomerProfileRepository extends JpaRepository<CustomerProfile, UUID> {

    Optional<CustomerProfile> findByUser_Id(UUID userId);

    boolean existsByUser_Id(UUID userId);

    Page<CustomerProfile> findAllByCreatedBy(User createdBy, Pageable pageable);
}