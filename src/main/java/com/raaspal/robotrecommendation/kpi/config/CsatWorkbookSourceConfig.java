package com.raaspal.robotrecommendation.kpi.config;

import com.raaspal.robotrecommendation.kpi.csat.BucketCsatWorkbookSource;
import com.raaspal.robotrecommendation.kpi.csat.CsatWorkbookSource;
import com.raaspal.robotrecommendation.kpi.csat.FolderCsatWorkbookSource;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Which source the CSAT service reads: the bucket wherever one is configured,
 * the folder otherwise.
 *
 * <p>Chosen here rather than by a profile, because the same rule is right
 * everywhere. A deployed console has bucket credentials and no folder worth
 * reading — Render's disk is wiped on every deploy. A developer's machine has
 * the workbooks in Downloads and usually no credentials. Either way the answer
 * follows from the configuration, and {@code /kpi/csat/source} says which one
 * answered.
 */
@Slf4j
@Configuration
public class CsatWorkbookSourceConfig {

    @Bean
    public CsatWorkbookSource csatWorkbookSource(KpiCsatProperties properties) {
        if (properties.getBucket().isConfigured()) {
            BucketCsatWorkbookSource source = new BucketCsatWorkbookSource(properties);
            log.info("CSAT workbooks: reading {}", source.describe());
            return source;
        }
        FolderCsatWorkbookSource source = new FolderCsatWorkbookSource(properties);
        log.info("CSAT workbooks: reading folder {} (no bucket configured)", source.describe());
        return source;
    }
}
