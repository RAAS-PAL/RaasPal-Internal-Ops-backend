package com.raaspal.robotrecommendation.kpi.config;

import com.raaspal.robotrecommendation.kpi.csat.CsatWorkbookSource;
import com.raaspal.robotrecommendation.kpi.csat.DatabaseCsatWorkbookSource;
import com.raaspal.robotrecommendation.kpi.csat.FolderCsatWorkbookSource;
import com.raaspal.robotrecommendation.kpi.repository.KpiCsatWorkbookRepository;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Which source the CSAT service reads: the uploaded workbooks in the database,
 * unless a folder is explicitly configured.
 *
 * <p>The database is the answer everywhere it matters. A folder is an escape
 * hatch for working on the parser against a pile of files locally without
 * uploading them one at a time — set {@code app.kpi.csat.folder} and it wins,
 * leave it blank and uploads do. The precedence is that way round because
 * setting the folder is a deliberate local act, while every deployment simply
 * does not set it.
 *
 * <p>This used to choose between an S3 bucket and that folder.
 * {@code /kpi/csat/source} still says which source answered.
 */
@Slf4j
@Configuration
public class CsatWorkbookSourceConfig {

    @Bean
    public CsatWorkbookSource csatWorkbookSource(KpiCsatProperties properties,
                                                 KpiCsatWorkbookRepository workbooks) {
        String folder = properties.getFolder();
        if (folder != null && !folder.isBlank()) {
            FolderCsatWorkbookSource source = new FolderCsatWorkbookSource(properties);
            log.info("CSAT workbooks: reading folder {} (app.kpi.csat.folder is set)", source.describe());
            return source;
        }
        log.info("CSAT workbooks: reading uploads from the database");
        return new DatabaseCsatWorkbookSource(workbooks);
    }
}
