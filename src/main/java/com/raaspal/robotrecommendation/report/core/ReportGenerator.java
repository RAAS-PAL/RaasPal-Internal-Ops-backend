package com.raaspal.robotrecommendation.report.core;

import com.raaspal.robotrecommendation.telemetry.entity.RobotTaskReport;

import java.io.IOException;
import java.util.List;

/**
 * Brand-specific monthly report generator. Each robot brand (Gausium, etc.)
 * provides one implementation that knows how to lay out that brand's
 * "task-queue-list" report from its {@link RobotTaskReport} rows.
 */
public interface ReportGenerator {

    /** The brand this generator handles, e.g. "GAUSIUM". */
    String getBrand();

    /** Whether this generator handles the given brand (case-insensitive). */
    boolean supports(String brand);

    /**
     * Generates the monthly report workbook as raw {@code .xlsx} bytes.
     * Callers must ensure each report's {@code robotUnit} is already fetched
     * (not lazy), since it is read outside of any persistence context here.
     */
    byte[] generate(List<RobotTaskReport> reports) throws IOException;
}
