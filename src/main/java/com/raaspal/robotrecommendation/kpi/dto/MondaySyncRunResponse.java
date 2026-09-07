package com.raaspal.robotrecommendation.kpi.dto;

import com.raaspal.robotrecommendation.kpi.entity.CaseTicketSyncRun;
import com.raaspal.robotrecommendation.kpi.entity.ServiceLine;

import java.time.LocalDateTime;
import java.util.UUID;

/** One row of the sync history shown next to the dashboard's "data as of" stamp. */
public record MondaySyncRunResponse(
        UUID id,
        String boardId,
        ServiceLine serviceLine,
        CaseTicketSyncRun.Status status,
        CaseTicketSyncRun.Trigger triggeredBy,
        LocalDateTime startedAt,
        LocalDateTime finishedAt,
        int groupsRead,
        int itemsRead,
        int itemsInserted,
        int itemsUpdated,
        int itemsUnchanged,
        int itemsMarkedAbsent,
        String errorMessage
) {

    public static MondaySyncRunResponse from(CaseTicketSyncRun run) {
        return new MondaySyncRunResponse(
                run.getId(),
                run.getSourceBoardId(),
                run.getServiceLine(),
                run.getStatus(),
                run.getTriggeredBy(),
                run.getStartedAt(),
                run.getFinishedAt(),
                run.getGroupsRead(),
                run.getItemsRead(),
                run.getItemsInserted(),
                run.getItemsUpdated(),
                run.getItemsUnchanged(),
                run.getItemsMarkedAbsent(),
                run.getErrorMessage());
    }
}
