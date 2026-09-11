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

/**
 * Makes sure the MK report definition exists.
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
 * bug to chase.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class CaseReportDefinitionSeeder implements ApplicationRunner {

    private final CaseReportDefinitionRepository definitions;

    @Override
    @Transactional
    public void run(ApplicationArguments args) {
        if (definitions.findByCode(CaseReportDefinition.MK_PENDING).isPresent()) {
            return;
        }

        definitions.save(CaseReportDefinition.builder()
                .code(CaseReportDefinition.MK_PENDING)
                .name("MK pending cases")
                .description("Delivery cases for MK, Yayoi and Bonus Suki — one customer, "
                        + "three brands, so one SLA covers all three.")
                .generatorKey("MK_PENDING")
                .source(CaseSource.MONDAY)
                .sourceBoardId("1647612496")
                .sourceGroupId("group_title")
                // MANUAL until the generated report has been checked against the
                // hand-built one enough times to be trusted. AUTO would send it.
                .deliveryMode("MANUAL")
                .scheduleZone("Asia/Bangkok")
                .slaDaysMetro(3)
                .slaDaysUpcountry(5)
                .isActive(true)
                .build());

        log.info("Seeded the {} report definition", CaseReportDefinition.MK_PENDING);
    }
}
