package com.raaspal.robotrecommendation.robot.service;

import com.raaspal.robotrecommendation.common.exception.ResourceNotFoundException;
import com.raaspal.robotrecommendation.common.enums.TestStatus;
import com.raaspal.robotrecommendation.recommendation.repository.RecommendationItemRepository;
import com.raaspal.robotrecommendation.robot.dto.RobotRequest;
import com.raaspal.robotrecommendation.robot.dto.RobotResponse;
import com.raaspal.robotrecommendation.robot.dto.RobotSpecMatrixRow;
import com.raaspal.robotrecommendation.robot.dto.RobotSpecRequest;
import com.raaspal.robotrecommendation.robot.dto.RobotSpecResponse;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.raaspal.robotrecommendation.robot.entity.Robot;
import com.raaspal.robotrecommendation.robot.entity.RobotSpec;
import com.raaspal.robotrecommendation.robot.repository.RobotRepository;
import com.raaspal.robotrecommendation.robot.repository.RobotSpecRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

@Service
@RequiredArgsConstructor
public class RobotService {

    /**
     * Column order from {@code to_jsonb} is the table's own, which is the order the
     * datasheet defines. A LinkedHashMap preserves it so the UI does not have to
     * re-sort 101 fields to look like the source document.
     */
    private static final ObjectMapper SPEC_JSON = new ObjectMapper();

    private final RobotRepository robotRepository;
    private final RobotSpecRepository robotSpecRepository;
    private final RecommendationItemRepository recommendationItemRepository;

    @Transactional(readOnly = true)
    public Page<RobotResponse> getAll(Pageable pageable) {
        return robotRepository.findAll(pageable).map(this::toResponse);
    }

    /**
     * Every cleaning model that has specs, for the side-by-side comparison matrix.
     *
     * <p>Not paginated: the whole point is comparing models against each other, and
     * a page boundary through the middle of that defeats it. The catalogue is a
     * dozen-odd models, so the payload stays small even at 101 columns each.
     */
    @Transactional(readOnly = true)
    public List<RobotSpecMatrixRow> getCleaningSpecMatrix() {
        return robotRepository.findCleaningSpecMatrix().stream()
                .map(row -> new RobotSpecMatrixRow(
                        (UUID) row[0],
                        (String) row[1],
                        (String) row[2],
                        (String) row[3],
                        parseSpecs((String) row[4])))
                .toList();
    }

    private Map<String, Object> parseSpecs(String json) {
        if (json == null || json.isBlank()) {
            return Map.of();
        }
        try {
            return SPEC_JSON.readValue(json, new TypeReference<LinkedHashMap<String, Object>>() {});
        } catch (JsonProcessingException e) {
            // A malformed spec row must not take down the whole matrix — the other
            // models still render, and the gap is visible in the UI.
            return Map.of();
        }
    }

    @Transactional(readOnly = true)
    public Robot getEntity(UUID id) {
        return robotRepository.findById(id)
                .orElseThrow(() -> new ResourceNotFoundException("Robot", "id", id));
    }

    @Transactional(readOnly = true)
    public RobotResponse getById(UUID id) {
        return toResponse(getEntity(id));
    }

    @Transactional
    public RobotResponse create(RobotRequest request) {
        robotRepository.findByBrandIgnoreCaseAndModelIgnoreCase(request.brand(), request.model())
                .ifPresent(robot -> {
                    throw new IllegalArgumentException("Robot already exists with brand and model");
                });

        Robot robot = Robot.builder()
                .brand(request.brand())
                .model(request.model())
                .robotType(request.robotType())
                .testStatus(request.testStatus() == null ? TestStatus.DRAFT : request.testStatus())
                .priceBand(request.priceBand())
                .rentalPrice(request.rentalPrice())
                .sellingPrice(request.sellingPrice())
                .imageUrl(request.imageUrl())
                .datasheetUrl(request.datasheetUrl())
                .build();

        Robot savedRobot = robotRepository.save(robot);
        if (request.spec() != null) {
            robotSpecRepository.save(toSpecEntity(request.spec(), savedRobot));
        }

        return toResponse(savedRobot);
    }

    @Transactional
    public RobotResponse update(UUID id, RobotRequest request) {
        Robot robot = getEntity(id);
        robot.setBrand(request.brand());
        robot.setModel(request.model());
        robot.setRobotType(request.robotType());
        robot.setTestStatus(request.testStatus() == null ? TestStatus.DRAFT : request.testStatus());
        robot.setPriceBand(request.priceBand());
        robot.setRentalPrice(request.rentalPrice());
        robot.setSellingPrice(request.sellingPrice());
        robot.setImageUrl(request.imageUrl());
        robot.setDatasheetUrl(request.datasheetUrl());

        Robot savedRobot = robotRepository.save(robot);
        if (request.spec() != null) {
            RobotSpec spec = robotSpecRepository.findByRobot_Id(savedRobot.getId())
                    .orElseGet(() -> RobotSpec.builder().robot(savedRobot).build());
            applySpec(request.spec(), spec);
            robotSpecRepository.save(spec);
        }

        return toResponse(savedRobot);
    }

    @Transactional
    public void delete(UUID id) {
        Robot robot = getEntity(id);
        if (recommendationItemRepository.existsByRobot_Id(id)) {
            throw new IllegalStateException("Cannot delete robot: it is referenced by existing recommendations.");
        }
        robotSpecRepository.findByRobot_Id(id).ifPresent(robotSpecRepository::delete);
        robotRepository.delete(robot);
    }

    private RobotResponse toResponse(Robot robot) {
        Optional<RobotSpec> spec = robotSpecRepository.findByRobot_Id(robot.getId());
        return spec
                .map(robotSpec -> RobotResponse.from(robot, RobotSpecResponse.from(robotSpec)))
                .orElseGet(() -> RobotResponse.from(robot));
    }

    private RobotSpec toSpecEntity(RobotSpecRequest request, Robot robot) {
        RobotSpec spec = RobotSpec.builder().robot(robot).build();
        applySpec(request, spec);
        return spec;
    }

    private void applySpec(RobotSpecRequest request, RobotSpec spec) {
        spec.setLengthMm(request.lengthMm());
        spec.setWidthMm(request.widthMm());
        spec.setHeightMm(request.heightMm());
        spec.setRobotWeightKg(request.robotWeightKg());
        spec.setWidthCleaningMm(request.widthCleaningMm());
        spec.setBrushPressureKg(request.brushPressureKg());
        spec.setVacuumPressureKpa(request.vacuumPressureKpa());
        spec.setSpeedMs(request.speedMs());
        spec.setNoiseLevelDb(request.noiseLevelDb());
        spec.setWorkStation(request.workStation());
        spec.setDockCharge(request.dockCharge());
        spec.setManualCharge(request.manualCharge());
        spec.setCleaningEfficiencySweepSqmH(request.cleaningEfficiencySweepSqmH());
        spec.setCleaningEfficiencyScrubSqmH(request.cleaningEfficiencyScrubSqmH());
        spec.setCleaningEfficiencyMopSqmH(request.cleaningEfficiencyMopSqmH());
        spec.setCleaningEfficiencySweepScrubSqmH(request.cleaningEfficiencySweepScrubSqmH());
        spec.setCleaningEfficiencyVacuumSqmH(request.cleaningEfficiencyVacuumSqmH());
        spec.setTankCapacityCleanL(request.tankCapacityCleanL());
        spec.setTankCapacityWasteL(request.tankCapacityWasteL());
        spec.setTankCapacityTrashL(request.tankCapacityTrashL());
        spec.setTankCapacityDustBagL(request.tankCapacityDustBagL());
        spec.setCleaningFunctionSweepNoVacuum(request.cleaningFunctionSweepNoVacuum());
        spec.setCleaningFunctionSweepVacuum(request.cleaningFunctionSweepVacuum());
        spec.setCleaningFunctionMopDry(request.cleaningFunctionMopDry());
        spec.setCleaningFunctionMopWet(request.cleaningFunctionMopWet());
        spec.setCleaningFunctionScrubBrushRoller(request.cleaningFunctionScrubBrushRoller());
        spec.setCleaningFunctionScrubBrushDisc(request.cleaningFunctionScrubBrushDisc());
        spec.setNavigationLidar2d(request.navigationLidar2d());
        spec.setNavigationLidar3d(request.navigationLidar3d());
        spec.setNavigationCameraVslam(request.navigationCameraVslam());
        spec.setBatteryType(request.batteryType());
        spec.setBatteryVoltageV(request.batteryVoltageV());
        spec.setBatteryCapacityAh(request.batteryCapacityAh());
        spec.setBatteryChargingTimeHr(request.batteryChargingTimeHr());
        spec.setBatteryWorkTimeSweepHr(request.batteryWorkTimeSweepHr());
        spec.setBatteryWorkTimeScrubHr(request.batteryWorkTimeScrubHr());
        spec.setBatteryWorkTimeSweepVacuumHr(request.batteryWorkTimeSweepVacuumHr());
        spec.setMinimumPassableWidthMm(request.minimumPassableWidthMm());
        spec.setMinimumPassableHeightMm(request.minimumPassableHeightMm());
        spec.setMaximumNarrowCrossMm(request.maximumNarrowCrossMm());
        spec.setMinimumTurnWidthMm(request.minimumTurnWidthMm());
        spec.setMinimumEdgeFromWallMm(request.minimumEdgeFromWallMm());
        spec.setMaximumStepHeightMm(request.maximumStepHeightMm());
        spec.setSlopeAngleDeg(request.slopeAngleDeg());
        spec.setSpotAi(request.spotAi());
        spec.setOutdoorIndoor(request.outdoorIndoor());
        spec.setIpRating(request.ipRating());
        spec.setHepa(request.hepa());
        spec.setFloorTypePavingBlocks(request.floorTypePavingBlocks());
        spec.setFloorTypeGranite(request.floorTypeGranite());
        spec.setFloorTypeMarble(request.floorTypeMarble());
        spec.setFloorTypeTerrazzo(request.floorTypeTerrazzo());
        spec.setFloorTypeTerracotta(request.floorTypeTerracotta());
        spec.setFloorTypeCeramic(request.floorTypeCeramic());
        spec.setFloorTypeSmoothConcrete(request.floorTypeSmoothConcrete());
        spec.setFloorTypeCoarseConcrete(request.floorTypeCoarseConcrete());
        spec.setFloorTypeStampedConcrete(request.floorTypeStampedConcrete());
        spec.setFloorTypeAsphalt(request.floorTypeAsphalt());
        spec.setFloorTypeEpoxy(request.floorTypeEpoxy());
        spec.setFloorTypeTile(request.floorTypeTile());
        spec.setFloorTypeShortCarpet(request.floorTypeShortCarpet());
        spec.setFloorTypeLongCarpet(request.floorTypeLongCarpet());
        spec.setFloorTypeSpc(request.floorTypeSpc());
        spec.setFloorTypeLaminate(request.floorTypeLaminate());
        spec.setFloorTypeVinyl(request.floorTypeVinyl());
        spec.setFloorLayout2x2(request.floorLayout2x2());
        spec.setFloorLayout4x4(request.floorLayout4x4());
        spec.setFloorLayout8x8(request.floorLayout8x8());
        spec.setFloorLayout10x10(request.floorLayout10x10());
        spec.setFloorLayout12x12(request.floorLayout12x12());
        spec.setFloorLayout20x20(request.floorLayout20x20());
    }
}
