package com.raaspal.robotrecommendation.cvte.repository;

import com.raaspal.robotrecommendation.cvte.entity.CvteDevice;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.Optional;

@Repository
public interface CvteDeviceRepository extends JpaRepository<CvteDevice, java.util.UUID> {

    Optional<CvteDevice> findByDeviceId(Long deviceId);

    Optional<CvteDevice> findByFactorySn(String factorySn);

    @Query("SELECT d FROM CvteDevice d WHERE " +
            "(:factorySn IS NULL OR LOWER(d.factorySn) LIKE LOWER(CONCAT('%', CAST(:factorySn AS string), '%'))) AND " +
            "(:deviceName IS NULL OR LOWER(d.deviceName) LIKE LOWER(CONCAT('%', CAST(:deviceName AS string), '%'))) AND " +
            "(:orgCode IS NULL OR LOWER(d.orgCode) LIKE LOWER(CONCAT('%', CAST(:orgCode AS string), '%')))")
    Page<CvteDevice> search(
            @Param("factorySn") String factorySn,
            @Param("deviceName") String deviceName,
            @Param("orgCode") String orgCode,
            Pageable pageable
    );
}
