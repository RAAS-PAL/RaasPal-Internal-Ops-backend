package com.raaspal.robotrecommendation.kpi.config;

import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * Where the CSAT survey workbooks live. See the {@code app.kpi.csat.*} block in
 * {@code application.properties}.
 *
 * <p>CSAT is the one RE KPI with no monday source: it comes from the post-job
 * phone survey, which the RE team tallies by hand into four workbooks (one per
 * survey) once a month. So this KPI is deliberately not live — it changes when
 * the workbooks are replaced, and the console says so.
 *
 * <p>They are replaced by uploading them in the console, which stores them in
 * {@code kpi_csat_workbook}. {@link #folder} is a local escape hatch only: set
 * it and the backend reads that folder instead of the uploads.
 */
@Getter
@Setter
@Component
@ConfigurationProperties(prefix = "app.kpi.csat")
public class KpiCsatProperties {

    /** Read workbooks from this folder instead of the uploaded ones. Local development only. */
    private String folder;

    /** Zone for reporting file timestamps; the team is in Bangkok. */
    private String zone = "Asia/Bangkok";
}
