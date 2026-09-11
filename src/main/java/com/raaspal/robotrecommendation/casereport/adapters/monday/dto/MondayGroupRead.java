package com.raaspal.robotrecommendation.casereport.adapters.monday.dto;

import java.util.List;

/**
 * Every item read from one group, plus whether the read reached the end.
 *
 * <p>{@code complete} is false only when the page guard stopped the read with a
 * cursor still open. A caller that marks unseen rows as gone must not do so on
 * an incomplete read, or a big group would "delete" its own tail.
 */
public record MondayGroupRead(List<MondayItem> items, boolean complete) {
}
