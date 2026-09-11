package com.raaspal.robotrecommendation.kpi.entity;

/**
 * Which board family a ticket came from. The RE KPIs treat the two completely
 * differently: an installation is scored on whether a CM followed it, a CM on
 * whether another CM followed it.
 */
public enum TicketType {
    INSTALLATION,
    CM
}
