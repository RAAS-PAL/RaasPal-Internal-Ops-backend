package com.raaspal.robotrecommendation.robotunit.entity;

/**
 * How often a deployed robot's performance report is generated and sent.
 * Stored per {@link Deployment} so different customers/robots can run on
 * different schedules. {@code OFF} excludes the robot from scheduled sends
 * (manual runs can still target it).
 */
public enum ReportCadence {
    MONTHLY,
    WEEKLY,
    OFF
}
