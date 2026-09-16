package com.raaspal.robotrecommendation.casereport.service;

import com.raaspal.robotrecommendation.casereport.entity.CaseReportDefinition;
import com.raaspal.robotrecommendation.casereport.entity.CaseSource;
import com.raaspal.robotrecommendation.casereport.repository.CaseReportDefinitionRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

/**
 * Makes sure every report definition exists.
 *
 * <p>In code rather than a seed migration on purpose, and only for now. A report is
 * configuration, and configuration belongs in a row somebody can edit — but the console
 * has no screen for creating one yet, and a definition has to exist before a run can
 * reference it. Seeding here keeps the feature runnable without adding a migration that
 * would then need its own correction once the real values are known.
 *
 * <p><strong>Only creates; never updates.</strong> Once a row exists, whatever is in it
 * wins — including SLA days someone corrected in the database. A seeder that overwrote on
 * every boot would silently undo their change at the next restart, which is a maddening
 * bug to chase. Each definition is checked on its own, so adding one here creates just
 * that one on the next start.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class CaseReportDefinitionSeeder implements ApplicationRunner {

    private final CaseReportDefinitionRepository definitions;

    @Override
    @Transactional
    public void run(ApplicationArguments args) {
        for (CaseReportDefinition wanted : wanted()) {
            if (definitions.findByCode(wanted.getCode()).isPresent()) {
                continue;
            }
            definitions.save(wanted);
            log.info("Seeded the {} report definition", wanted.getCode());
        }
    }

    private static List<CaseReportDefinition> wanted() {
        return List.of(
                CaseReportDefinition.builder()
                        .code(CaseReportDefinition.MK_PENDING)
                        .name("MK pending cases")
                        .description("Delivery cases for MK, Yayoi and Bonus Suki — one "
                                + "customer, three brands, so one SLA covers all three.")
                        .generatorKey("MK_PENDING")
                        .source(CaseSource.MONDAY)
                        .sourceBoardId(MkPendingReportGenerator.BOARD_ID)
                        .sourceGroupId(MkPendingReportGenerator.GROUP_ID)
                        // MANUAL until the generated report has been checked against the
                        // hand-built one enough times to be trusted. AUTO would send it.
                        .deliveryMode("MANUAL")
                        .scheduleZone("Asia/Bangkok")
                        .slaDaysMetro(3)
                        .slaDaysUpcountry(5)
                        .isActive(true)
                        .build(),

                CaseReportDefinition.builder()
                        .code(CaseReportDefinition.CLEANING_PENDING)
                        .name("Cleaning pending cases")
                        .description("Every open cleaning case except Makro's and the "
                                + "airports', which have their own sheets. 3-day SLA "
                                + "everywhere.")
                        .generatorKey("CLEANING_PENDING")
                        .source(CaseSource.MONDAY)
                        .sourceBoardId(CleaningPendingReportGenerator.BOARD_ID)
                        .sourceGroupId(CleaningPendingReportGenerator.GROUP_ID)
                        .deliveryMode("MANUAL")
                        .scheduleZone("Asia/Bangkok")
                        // Equal on purpose: the calculator then never consults the
                        // province, and the cleaning board has none.
                        .slaDaysMetro(3)
                        .slaDaysUpcountry(3)
                        .isActive(true)
                        .build(),

                CaseReportDefinition.builder()
                        .code(CaseReportDefinition.MAKRO_PENDING)
                        .name("Makro pending cases")
                        .description("Makro's cleaning cases, on their own sheet as the RE "
                                + "team sends them. 3-day SLA everywhere.")
                        .generatorKey("MAKRO_PENDING")
                        .source(CaseSource.MONDAY)
                        .sourceBoardId(CleaningPendingReportGenerator.BOARD_ID)
                        .sourceGroupId(CleaningPendingReportGenerator.GROUP_ID)
                        .deliveryMode("MANUAL")
                        .scheduleZone("Asia/Bangkok")
                        .slaDaysMetro(3)
                        .slaDaysUpcountry(3)
                        .isActive(true)
                        .build(),

                CaseReportDefinition.builder()
                        .code(CaseReportDefinition.AOTGA_PENDING)
                        .name("AOTGA pending cases")
                        .description("The airports' open cleaning cases, tracked by "
                                + "spare-part turnaround rather than SLA: what was "
                                + "ordered, who it is waited on, when it arrived. 3-day "
                                + "SLA computed for the review table; the sheet does "
                                + "not print it.")
                        .generatorKey("AOTGA_PENDING")
                        .source(CaseSource.MONDAY)
                        .sourceBoardId(AotgaReportGenerator.BOARD_ID)
                        .sourceGroupId(AotgaReportGenerator.GROUP_ID)
                        .deliveryMode("MANUAL")
                        .scheduleZone("Asia/Bangkok")
                        .slaDaysMetro(3)
                        .slaDaysUpcountry(3)
                        .isActive(true)
                        .build());
    }
}
