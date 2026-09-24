package com.raaspal.robotrecommendation.mkstock.repository;

import com.raaspal.robotrecommendation.mkstock.entity.MkPartImage;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.UUID;

@Repository
public interface MkPartImageRepository extends JpaRepository<MkPartImage, UUID> {

    /** Which parts have a photo - ids only, never the image data. */
    @Query("select i.partId from MkPartImage i")
    List<UUID> partIdsWithImage();
}
