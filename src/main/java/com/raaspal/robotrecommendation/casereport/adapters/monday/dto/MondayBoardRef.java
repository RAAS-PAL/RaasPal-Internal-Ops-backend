package com.raaspal.robotrecommendation.casereport.adapters.monday.dto;

/**
 * A board in a listing: enough to identify it, nothing from inside it.
 * {@code state} is monday's own ("active", "archived", "deleted").
 */
public record MondayBoardRef(String id, String name, String state) {
}
