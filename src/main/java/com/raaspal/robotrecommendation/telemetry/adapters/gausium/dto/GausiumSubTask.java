package com.raaspal.robotrecommendation.telemetry.adapters.gausium.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

/**
 * One entry of a task report's {@code subTasks} array. This is where Gausium
 * actually reports the <strong>map</strong> a task ran on — the top-level
 * {@code areaNameList} is empty for whole-map tasks, which is most of them.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record GausiumSubTask(
        String mapId,
        String mapName,
        Double actualCleaningAreaSquareMeter,
        String taskId
) {
}
