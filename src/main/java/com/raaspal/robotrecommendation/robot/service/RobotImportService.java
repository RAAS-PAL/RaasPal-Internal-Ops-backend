package com.raaspal.robotrecommendation.robot.service;

import com.raaspal.robotrecommendation.common.enums.RobotType;
import com.raaspal.robotrecommendation.common.enums.TestStatus;
import com.raaspal.robotrecommendation.robot.dto.RobotImportResult;
import com.raaspal.robotrecommendation.robot.entity.Robot;
import com.raaspal.robotrecommendation.robot.entity.RobotSpec;
import com.raaspal.robotrecommendation.robot.repository.RobotRepository;
import com.raaspal.robotrecommendation.robot.repository.RobotSpecRepository;
import lombok.RequiredArgsConstructor;
import org.apache.commons.csv.CSVFormat;
import org.apache.commons.csv.CSVParser;
import org.apache.poi.ss.usermodel.Cell;
import org.apache.poi.ss.usermodel.DataFormatter;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.ss.usermodel.Workbook;
import org.apache.poi.ss.usermodel.WorkbookFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.multipart.MultipartFile;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

@Service
@RequiredArgsConstructor
public class RobotImportService {

    private static final int DATA_START_ROW = 3;
    private static final Pattern FIRST_NUMBER = Pattern.compile("-?\\d+(?:,\\d{3})*(?:\\.\\d+)?|-?\\d+(?:\\.\\d+)?");

    private final RobotRepository robotRepository;
    private final RobotSpecRepository robotSpecRepository;

    @Transactional
    public RobotImportResult importCatalog(MultipartFile file, RobotType robotType, TestStatus testStatus) {
        if (file == null || file.isEmpty()) {
            throw new IllegalArgumentException("Import file must not be empty");
        }

        RobotType effectiveRobotType = robotType == null ? RobotType.CLEANING : robotType;
        TestStatus effectiveTestStatus = testStatus == null ? TestStatus.DRAFT : testStatus;
        List<List<String>> rows = readRows(file);
        List<String> errors = new ArrayList<>();
        int imported = 0;
        int updated = 0;
        int skipped = 0;

        for (int rowIndex = DATA_START_ROW; rowIndex < rows.size(); rowIndex++) {
            List<String> row = rows.get(rowIndex);
            String brand = cell(row, 0);
            String model = cell(row, 1);

            if (isBlank(brand) && isBlank(model)) {
                continue;
            }
            if (isBlank(brand) || isBlank(model)) {
                skipped++;
                errors.add("Row " + (rowIndex + 1) + " skipped: brand and model are required");
                continue;
            }

            try {
                Optional<Robot> existing = robotRepository.findByBrandIgnoreCaseAndModelIgnoreCase(brand, model);
                Robot robot = existing.orElseGet(Robot::new);
                robot.setBrand(brand);
                robot.setModel(model);
                robot.setRobotType(effectiveRobotType);
                robot.setTestStatus(effectiveTestStatus);
                Robot savedRobot = robotRepository.save(robot);

                RobotSpec spec = robotSpecRepository.findByRobot_Id(savedRobot.getId())
                        .orElseGet(() -> RobotSpec.builder().robot(savedRobot).build());
                applySpec(row, spec);
                robotSpecRepository.save(spec);

                if (existing.isPresent()) {
                    updated++;
                } else {
                    imported++;
                }
            } catch (RuntimeException ex) {
                skipped++;
                errors.add("Row " + (rowIndex + 1) + " skipped: " + ex.getMessage());
            }
        }

        return new RobotImportResult(rows.size() <= DATA_START_ROW ? 0 : rows.size() - DATA_START_ROW,
                imported, updated, skipped, errors);
    }

    private List<List<String>> readRows(MultipartFile file) {
        String filename = file.getOriginalFilename() == null ? "" : file.getOriginalFilename().toLowerCase(Locale.ROOT);
        try {
            if (filename.endsWith(".csv")) {
                return readCsvRows(file);
            }
            if (filename.endsWith(".xls") || filename.endsWith(".xlsx")) {
                return readExcelRows(file);
            }
        } catch (IOException ex) {
            throw new IllegalArgumentException("Unable to read import file", ex);
        }

        throw new IllegalArgumentException("Robot import supports CSV, XLS, and XLSX files only");
    }

    private List<List<String>> readCsvRows(MultipartFile file) throws IOException {
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(file.getInputStream(), StandardCharsets.UTF_8));
             CSVParser parser = CSVFormat.DEFAULT.builder().setTrim(true).build().parse(reader)) {
            return parser.stream()
                    .map(record -> record.stream().map(this::clean).toList())
                    .toList();
        }
    }

    private List<List<String>> readExcelRows(MultipartFile file) throws IOException {
        DataFormatter formatter = new DataFormatter();
        try (Workbook workbook = WorkbookFactory.create(file.getInputStream())) {
            Sheet sheet = workbook.getSheetAt(0);
            List<List<String>> rows = new ArrayList<>();
            for (Row row : sheet) {
                List<String> values = new ArrayList<>();
                short lastCell = row.getLastCellNum();
                for (int cellIndex = 0; cellIndex < lastCell; cellIndex++) {
                    Cell cell = row.getCell(cellIndex, Row.MissingCellPolicy.RETURN_BLANK_AS_NULL);
                    values.add(clean(cell == null ? "" : formatter.formatCellValue(cell)));
                }
                rows.add(values);
            }
            return rows;
        }
    }

    private void applySpec(List<String> row, RobotSpec spec) {
        spec.setLengthMm(parseInteger(cell(row, 2)));
        spec.setWidthMm(parseInteger(cell(row, 3)));
        spec.setHeightMm(parseInteger(cell(row, 4)));
        spec.setRobotWeightKg(parseDecimal(cell(row, 5)));
        spec.setWidthCleaningMm(parseInteger(cell(row, 6)));
        spec.setBrushPressureKg(parseDecimal(cell(row, 7)));
        spec.setVacuumPressureKpa(parseDecimal(cell(row, 8)));
        spec.setSpeedMs(parseDecimal(cell(row, 9)));
        spec.setNoiseLevelDb(parseDecimal(cell(row, 10)));
        spec.setWorkStation(parseBoolean(cell(row, 11)));
        spec.setDockCharge(parseBoolean(cell(row, 12)));
        spec.setManualCharge(parseBoolean(cell(row, 13)));
        spec.setCleaningEfficiencySweepSqmH(parseInteger(cell(row, 14)));
        spec.setCleaningEfficiencyScrubSqmH(parseInteger(cell(row, 15)));
        spec.setCleaningEfficiencyMopSqmH(parseInteger(cell(row, 16)));
        spec.setCleaningEfficiencySweepScrubSqmH(parseInteger(cell(row, 17)));
        spec.setCleaningEfficiencyVacuumSqmH(parseInteger(cell(row, 18)));
        spec.setTankCapacityCleanL(parseDecimal(cell(row, 19)));
        spec.setTankCapacityWasteL(parseDecimal(cell(row, 20)));
        spec.setTankCapacityTrashL(parseDecimal(cell(row, 21)));
        spec.setTankCapacityDustBagL(parseDecimal(cell(row, 22)));
        spec.setCleaningFunctionSweepNoVacuum(parseBoolean(cell(row, 23)));
        spec.setCleaningFunctionSweepVacuum(parseBoolean(cell(row, 24)));
        spec.setCleaningFunctionMopDry(parseBoolean(cell(row, 25)));
        spec.setCleaningFunctionMopWet(parseBoolean(cell(row, 26)));
        spec.setCleaningFunctionScrubBrushRoller(parseBoolean(cell(row, 27)));
        spec.setCleaningFunctionScrubBrushDisc(parseBoolean(cell(row, 28)));
        spec.setNavigationLidar2d(parseBoolean(cell(row, 29)));
        spec.setNavigationLidar3d(parseBoolean(cell(row, 30)));
        spec.setNavigationCameraVslam(parseBoolean(cell(row, 31)));
        spec.setBatteryType(blankToNull(cell(row, 32)));
        spec.setBatteryVoltageV(parseDecimal(cell(row, 33)));
        spec.setBatteryCapacityAh(parseDecimal(cell(row, 34)));
        spec.setBatteryChargingTimeHr(parseDecimal(cell(row, 35)));
        spec.setBatteryWorkTimeSweepHr(parseDecimal(cell(row, 36)));
        spec.setBatteryWorkTimeScrubHr(parseDecimal(cell(row, 37)));
        spec.setBatteryWorkTimeSweepVacuumHr(parseDecimal(cell(row, 38)));
        spec.setMinimumPassableWidthMm(parseInteger(cell(row, 39)));
        spec.setMinimumPassableHeightMm(parseInteger(cell(row, 40)));
        spec.setMaximumNarrowCrossMm(parseInteger(cell(row, 41)));
        spec.setMinimumTurnWidthMm(parseInteger(cell(row, 42)));
        spec.setMinimumEdgeFromWallMm(parseInteger(cell(row, 43)));
        spec.setMaximumStepHeightMm(parseInteger(cell(row, 44)));
        spec.setSlopeAngleDeg(parseDecimal(cell(row, 45)));
        spec.setSpotAi(parseBoolean(cell(row, 46)));
        spec.setOutdoorIndoor(blankToNull(cell(row, 47)));
        spec.setIpRating(blankToNull(cell(row, 48)));
        spec.setHepa(parseBoolean(cell(row, 49)));
        spec.setFloorTypePavingBlocks(parseBoolean(cell(row, 50)));
        spec.setFloorTypeGranite(parseBoolean(cell(row, 51)));
        spec.setFloorTypeMarble(parseBoolean(cell(row, 52)));
        spec.setFloorTypeTerrazzo(parseBoolean(cell(row, 53)));
        spec.setFloorTypeTerracotta(parseBoolean(cell(row, 54)));
        spec.setFloorTypeCeramic(parseBoolean(cell(row, 55)));
        spec.setFloorTypeSmoothConcrete(parseBoolean(cell(row, 56)));
        spec.setFloorTypeCoarseConcrete(parseBoolean(cell(row, 57)));
        spec.setFloorTypeStampedConcrete(parseBoolean(cell(row, 58)));
        spec.setFloorTypeAsphalt(parseBoolean(cell(row, 59)));
        spec.setFloorTypeEpoxy(parseBoolean(cell(row, 60)));
        spec.setFloorTypeTile(parseBoolean(cell(row, 61)));
        spec.setFloorTypeShortCarpet(parseBoolean(cell(row, 62)));
        spec.setFloorTypeLongCarpet(parseBoolean(cell(row, 63)));
        spec.setFloorTypeSpc(parseBoolean(cell(row, 64)));
        spec.setFloorTypeLaminate(parseBoolean(cell(row, 65)));
        spec.setFloorTypeVinyl(parseBoolean(cell(row, 66)));
        spec.setFloorLayout2x2(parseBoolean(cell(row, 67)));
        spec.setFloorLayout4x4(parseBoolean(cell(row, 68)));
        spec.setFloorLayout8x8(parseBoolean(cell(row, 69)));
        spec.setFloorLayout10x10(parseBoolean(cell(row, 70)));
        spec.setFloorLayout12x12(parseBoolean(cell(row, 71)));
        spec.setFloorLayout20x20(parseBoolean(cell(row, 72)));
    }

    private String cell(List<String> row, int index) {
        if (index >= row.size()) {
            return "";
        }
        return clean(row.get(index));
    }

    private String clean(String value) {
        if (value == null) {
            return "";
        }
        return value.replace("\uFEFF", "").trim();
    }

    private String blankToNull(String value) {
        return isBlank(value) || "-".equals(value.trim()) ? null : value.trim();
    }

    private boolean isBlank(String value) {
        return value == null || value.isBlank();
    }

    private Integer parseInteger(String value) {
        BigDecimal decimal = parseDecimal(value);
        return decimal == null ? null : decimal.intValue();
    }

    private BigDecimal parseDecimal(String value) {
        String normalized = blankToNull(value);
        if (normalized == null) {
            return null;
        }
        Matcher matcher = FIRST_NUMBER.matcher(normalized);
        if (!matcher.find()) {
            return null;
        }
        return new BigDecimal(matcher.group().replace(",", ""));
    }

    private Boolean parseBoolean(String value) {
        String normalized = blankToNull(value);
        if (normalized == null) {
            return null;
        }
        String upper = normalized.toUpperCase(Locale.ROOT);
        if (upper.startsWith("TRUE") || "YES".equals(upper) || "Y".equals(upper) || "1".equals(upper)) {
            return true;
        }
        if (upper.startsWith("FALSE") || "NO".equals(upper) || "N".equals(upper) || "0".equals(upper)) {
            return false;
        }
        return null;
    }
}
