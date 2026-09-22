package com.raaspal.robotrecommendation.reassignment.dto;

import com.raaspal.robotrecommendation.reassignment.service.ReAssignmentEvaluator.Candidate;
import com.raaspal.robotrecommendation.reassignment.service.ReAssignmentEvaluator.Exclusion;
import jakarta.validation.constraints.*;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/** Request and response shapes of {@code /api/v1/re-assignment}. */
public final class ReDtos {

    private ReDtos() {
    }

    /* ─── Engineers ──────────────────────────────────────────────────────── */

    public record EngineerRequest(
            @NotBlank @Size(max = 200) String fullName,
            @Size(max = 100) String nickname,
            @Email @Size(max = 200) String email,
            @Size(max = 50) String mondayUserId,
            @DecimalMin("0.5") @DecimalMax("100") BigDecimal maxLoad,
            @Size(max = 1000) String note,
            Boolean active) {
    }

    public record EngineerView(UUID id, String fullName, String nickname, String displayName, String email,
                               String mondayUserId, String mondayName, boolean active, BigDecimal maxLoad,
                               String note, BigDecimal load, int openTickets, int assessedSkills,
                               boolean onLeaveToday) {
    }

    public record LeaveRequest(@NotNull UUID engineerId, @NotNull LocalDate startsOn, @NotNull LocalDate endsOn,
                               @Size(max = 500) String note) {
    }

    public record LeaveView(UUID id, UUID engineerId, String engineerName, LocalDate startsOn, LocalDate endsOn,
                            String note, String createdBy) {
    }

    /** Someone seen in the board's RE column - to link an engineer to their monday account. */
    public record MondayPerson(String id, String name, int openTickets, UUID linkedEngineerId) {
    }

    /* ─── Skill matrix ───────────────────────────────────────────────────── */

    public record SkillView(String code, String groupCode, String boardType, String label, int ordinal) {
    }

    public record MatrixRow(UUID engineerId, String name, boolean active, Map<String, Integer> levels) {
    }

    public record RevisionView(UUID id, String label, String source, String reason, String createdBy,
                               Instant createdAt, long changes) {
    }

    public record MatrixView(List<SkillView> skills, List<MatrixRow> rows, List<RevisionView> revisions) {
    }

    /** One cell: level 1-4, or null for "-" / not assessed. */
    public record LevelChange(@NotNull UUID engineerId, @NotBlank String skillCode,
                              @Min(1) @Max(4) Integer level) {
    }

    public record MatrixUpdateRequest(@NotEmpty List<LevelChange> changes, @NotBlank @Size(max = 500) String reason) {
    }

    public record SkillHistoryEntry(String revisionLabel, String skillCode, Integer oldLevel, Integer newLevel,
                                    Instant changedAt) {
    }

    /* ─── Excel import ───────────────────────────────────────────────────── */

    public record ImportCell(String skillCode, Integer level, Integer currentLevel, String sourceCell,
                             String sourceValue) {
    }

    /** match: EXISTING (one engineer), NEW (will be created), AMBIGUOUS (blocks commit). */
    public record ImportRow(int sourceRow, String fullName, String nickname, String match, UUID engineerId,
                            String matchedName, List<ImportCell> cells, int changes, boolean unassessed) {
    }

    public record ImportPreview(String fileHash, String sheet, List<ImportRow> rows, List<String> unmappedColumns,
                                List<String> warnings, List<String> errors, int newEngineers, int changedLevels,
                                boolean canCommit) {
    }

    public record ImportResult(RevisionView revision, int engineersCreated, int levelsChanged) {
    }

    /* ─── Model mapping ──────────────────────────────────────────────────── */

    public record MappingView(String label, String disposition, String skillCode, String modelName, String note,
                              int openTickets, String updatedBy, Instant updatedAt) {
    }

    public record MappingRequest(@NotBlank @Size(max = 100) String label,
                                 @NotBlank @Pattern(regexp = "MAPPED|MANUAL|UNCONFIRMED") String disposition,
                                 String skillCode, @Size(max = 500) String note) {
    }

    /* ─── Queue and assignments ──────────────────────────────────────────── */

    public record AssignmentView(UUID id, String itemId, String ticketName, UUID engineerId, String engineerName,
                                 String status, String origin, BigDecimal score, Integer requiredLevel,
                                 String reason, String approvedBy, Instant approvedAt, Instant confirmedAt,
                                 Instant endedAt, String endedBy, String emailStatus, String emailDetail,
                                 String mondayStatus, String mondayDetail) {
    }

    public record QueueRow(String itemId, String name, String group, String status, String subStatus,
                           String modelLabel, String modelName, String issueLevel, String caseType,
                           String serviceMode, String serial, String customer, String branch, String mainIssue,
                           LocalDate openDate, LocalDate actionDate, List<String> people, String outcome,
                           String reason, Integer requiredLevel, boolean assumedDifficulty, String issueCategory,
                           Candidate suggested, List<Candidate> alternatives, List<Exclusion> excluded,
                           AssignmentView assignment, String mondayUrl, LocalDate forDate) {
    }

    public record QueueView(List<QueueRow> rows, Map<String, Integer> counts, Instant lastRefreshAt,
                            int openTickets, boolean emailEnabled, boolean canManage, int engineers,
                            int engineersWithoutMondayId, boolean mondayWriteEnabled) {
    }

    public record ApproveRequest(@NotBlank String itemId, @NotNull UUID engineerId,
                                 @Pattern(regexp = "SUGGESTION|ALTERNATIVE|MANUAL") String origin,
                                 @Size(max = 500) String reason, LocalDate bookedFrom, LocalDate bookedTo) {
    }

    /** Book an engineer on a job for some days; itemId optional. */
    public record ScheduleRequest(@NotNull UUID engineerId, String itemId, @NotNull LocalDate startsOn,
                                  @NotNull LocalDate endsOn, @Size(max = 500) String note) {
    }

    public record ScheduleView(UUID id, UUID engineerId, String engineerName, String itemId, String ticketName,
                               UUID assignmentId, LocalDate startsOn, LocalDate endsOn, String note,
                               String createdBy) {
    }

    public record ReasonRequest(@NotBlank @Size(max = 500) String reason) {
    }

    public record HoldRequest(@NotBlank String itemId, @NotBlank @Size(max = 500) String reason) {
    }

    public record RefreshResult(int seen, int openInActiveGroups, int newTickets, int closed, int confirmed,
                                int superseded, long durationMs) {
    }

    public record ManagerView(UUID userId, String email, String fullName, String role, String grantedBy,
                              Instant grantedAt) {
    }

    public record GrantRequest(@NotBlank @Email String email) {
    }

    public record AccessView(boolean canManage, boolean isAdmin) {
    }
}
