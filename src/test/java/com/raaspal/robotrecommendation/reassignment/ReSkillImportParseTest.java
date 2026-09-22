package com.raaspal.robotrecommendation.reassignment;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.raaspal.robotrecommendation.reassignment.entity.ReEngineer;
import com.raaspal.robotrecommendation.reassignment.entity.ReSkillLevel;
import com.raaspal.robotrecommendation.reassignment.repository.*;
import com.raaspal.robotrecommendation.reassignment.entity.ReSkillDefinition;
import com.raaspal.robotrecommendation.reassignment.repository.ReEngineerRepository;
import com.raaspal.robotrecommendation.reassignment.repository.ReSkillDefinitionRepository;
import com.raaspal.robotrecommendation.reassignment.dto.ReDtos.ImportPreview;
import com.raaspal.robotrecommendation.reassignment.dto.ReDtos.ImportRow;
import com.raaspal.robotrecommendation.reassignment.service.ReEventLog;
import com.raaspal.robotrecommendation.reassignment.service.ReSkillImportService;
import com.raaspal.robotrecommendation.reassignment.service.ReSkillMatrixService;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.ss.util.CellRangeAddress;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.springframework.jdbc.core.JdbcTemplate;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * The importer against a workbook shaped like the Senior RE's: title rows, a header row with
 * merged group cells, a skill-name row under it with the "Cleanning" spelling and CLN/DLV,
 * then one row per engineer. Names here are invented.
 */
class ReSkillImportParseTest {

    private final ReEngineerRepository engineers = mock(ReEngineerRepository.class);
    private final ReSkillDefinitionRepository skills = mock(ReSkillDefinitionRepository.class);
    private final ReSkillLevelRepository levels = mock(ReSkillLevelRepository.class);
    private final ReEventLog events = new ReEventLog(mock(ReEventRepository.class), new ObjectMapper());
    private final ReSkillMatrixService matrix = new ReSkillMatrixService(skills, levels,
            mock(ReSkillChangeRepository.class), mock(ReMatrixRevisionRepository.class), engineers, events, new JdbcTemplate());
    private final ReSkillImportService importer = new ReSkillImportService(engineers, skills, matrix, events);

    private final UUID existingId = UUID.randomUUID();

    private byte[] workbook() throws Exception {
        try (XSSFWorkbook wb = new XSSFWorkbook()) {
            wb.createSheet("Legend");
            Sheet s = wb.createSheet("Skill Matrix");
            s.createRow(0).createCell(0).setCellValue("RAAS PAL | RE Skill Matrix");
            Row g = s.createRow(4);
            Row h = s.createRow(5);
            String[][] cols = {
                    {"ลำดับ", ""}, {"ชื่อ-สกุล", ""}, {"ชื่อเล่น", ""},
                    {"Overall", "CLN"}, {"", "DLV"},
                    {"CM", "Cleanning"}, {"", "Delivery"},
                    {"Cleaning Robot Expertise", "Electrical"},
                    {"Cleaning Model", "Phantas"}, {"", "OMNIE"}, {"", "X-Human"},
                    {"", "L1-L2 Priorities"}, {"Mystery", "Thing"}};
            for (int i = 0; i < cols.length; i++) {
                g.createCell(i).setCellValue(cols[i][0]);
                h.createCell(i).setCellValue(cols[i][1]);
            }
            s.addMergedRegion(new CellRangeAddress(4, 4, 3, 4));   // Overall
            s.addMergedRegion(new CellRangeAddress(4, 4, 5, 6));   // CM
            s.addMergedRegion(new CellRangeAddress(4, 4, 8, 10));  // Cleaning Model
            String[][] people = {
                    {"1", "Somchai Existing", "Chai", "L4", "L3", "L4", "L2", "L3", "L4", "L4", "-", "0", ""},
                    {"2", "Newbie Person", "Nu", "L2", "", "L2", "L2", "L1", "L2", "L2", "-", "5", ""},
                    {"3", "Bad Level", "Bee", "L5", "", "L2", "", "", "", "", "", "", ""}};
            for (int r = 0; r < people.length; r++) {
                Row row = s.createRow(6 + r);
                for (int c = 0; c < people[r].length; c++) row.createCell(c).setCellValue(people[r][c]);
            }
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            wb.write(out);
            return out.toByteArray();
        }
    }

    private void stubRepos() {
        when(skills.findAll()).thenReturn(List.of(
                new ReSkillDefinition("PHANTAS", "MODEL", "CLEANING", "Phantas", 20),
                new ReSkillDefinition("OMNIE", "MODEL", "CLEANING", "OMNIE", 22),
                new ReSkillDefinition("X_HUMAN", "MODEL", "CLEANING", "X-Human", 27)));
        ReEngineer existing = ReEngineer.builder().id(existingId).fullName("Somchai Existing").nickname("Chai").build();
        when(engineers.findAll()).thenReturn(List.of(existing));
        when(levels.findAll()).thenReturn(List.of(ReSkillLevel.builder()
                .engineerId(existingId).skillCode("CM_CLEANING").level((short) 3).revisionId(UUID.randomUUID()).build()));
    }

    @Test
    void readsLevelsByGroupAndSkillAndMatchesEngineers() throws Exception {
        stubRepos();
        ImportPreview p = importer.preview(workbook());

        assertThat(p.sheet()).isEqualTo("Skill Matrix");
        assertThat(p.rows()).hasSize(3);

        ImportRow chai = p.rows().get(0);
        assertThat(chai.match()).isEqualTo("EXISTING");
        assertThat(chai.engineerId()).isEqualTo(existingId);
        Map<String, Integer> levels = new java.util.HashMap<>();
        chai.cells().forEach(c -> levels.put(c.skillCode(), c.level()));
        assertThat(levels).containsEntry("OVERALL_CLEANING", 4).containsEntry("OVERALL_DELIVERY", 3)
                .containsEntry("CM_CLEANING", 4).containsEntry("CM_DELIVERY", 2)
                .containsEntry("ELECTRICAL_CLEANING", 3).containsEntry("PHANTAS", 4)
                .containsEntry("OMNIE", 4).containsEntry("X_HUMAN", null);
        // CM was L3 in the database and is L4 in the workbook
        assertThat(chai.cells().stream().filter(c -> c.skillCode().equals("CM_CLEANING")).findFirst().orElseThrow()
                .currentLevel()).isEqualTo(3);

        assertThat(p.rows().get(1).match()).isEqualTo("NEW");
        assertThat(p.newEngineers()).isEqualTo(2);
        // the derived priorities column is skipped silently; the unknown one is reported
        assertThat(p.unmappedColumns()).singleElement().asString().contains("Mystery");
    }

    @Test
    void aLevelOutsideL1ToL4BlocksTheImport() throws Exception {
        stubRepos();
        ImportPreview p = importer.preview(workbook());

        assertThat(p.canCommit()).isFalse();
        assertThat(p.errors()).anyMatch(e -> e.contains("\"L5\" is not L1-L4"));
    }
}
