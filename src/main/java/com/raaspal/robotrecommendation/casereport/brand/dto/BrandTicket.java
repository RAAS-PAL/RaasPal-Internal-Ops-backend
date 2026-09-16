package com.raaspal.robotrecommendation.casereport.brand.dto;

import lombok.Builder;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;

/**
 * One ticket as the brand page and the export see it: the mapped board fields, the
 * unmapped ones lifted out of {@code raw_columns}, and the comment thread.
 */
@Builder
public record BrandTicket(
        String id,
        String itemId,
        String name,
        String group,
        /** True while the ticket has not reached a Done group or a Done status. */
        boolean open,
        String status,
        String supStatus,
        String project,
        String branch,
        String branchCode,
        String province,
        String model,
        String serial,
        String rootCause,
        String reOwner,
        String caseType,
        String level,
        String underWarranty,
        String channel,
        String mainIssue,
        String solution,
        LocalDate openDate,
        LocalDate reActionDate,
        /** Open Date to RE Action, when both are set. */
        Integer daysToAction,
        /** Days since Open Date, for open tickets only. */
        Integer ageDays,
        LocalDateTime sourceUpdatedAt,
        LocalDateTime firstSeenAt,
        LocalDateTime lastSyncedAt,
        /** Deep link into monday, or null when {@code app.tickets.monday-web-url} is blank. */
        String mondayUrl,
        List<Comment> comments
) {

    @Builder
    public record Comment(
            String id,
            String parentId,
            String author,
            LocalDateTime postedAt,
            String body
    ) {
    }
}
