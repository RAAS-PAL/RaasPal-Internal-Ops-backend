package com.raaspal.robotrecommendation.reassignment.controller;

import com.raaspal.robotrecommendation.auth.security.UserPrincipal;
import com.raaspal.robotrecommendation.common.exception.BadRequestException;
import com.raaspal.robotrecommendation.common.response.ApiResponse;
import com.raaspal.robotrecommendation.reassignment.dto.ReDtos.*;
import com.raaspal.robotrecommendation.reassignment.entity.ReAssignment;
import com.raaspal.robotrecommendation.reassignment.entity.ReEngineer;
import com.raaspal.robotrecommendation.reassignment.entity.ReManagerGrant;
import com.raaspal.robotrecommendation.reassignment.repository.ReManagerGrantRepository;
import com.raaspal.robotrecommendation.reassignment.service.*;
import com.raaspal.robotrecommendation.user.entity.User;
import com.raaspal.robotrecommendation.user.repository.UserRepository;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.util.*;

/**
 * RE assignment for CM tickets: {@code /api/v1/re-assignment}.
 *
 * <p>ADMIN and RAASPAL_TEAM may view the queue; everything that changes data - engineers,
 * skill levels, mappings, approvals - needs ADMIN or a Senior RE grant
 * ({@link ReAccessService#requireManage}). The skill matrix itself is manage-only to read,
 * because it is a personal assessment of named employees.
 */
@RestController
@RequestMapping("/api/v1/re-assignment")
@RequiredArgsConstructor
@PreAuthorize("hasAnyRole('ADMIN','RAASPAL_TEAM')")
public class ReAssignmentController {

    private final ReAccessService access;
    private final ReQueueService queue;
    private final ReTicketRefreshService refresh;
    private final ReAssignmentService assignmentService;
    private final ReEngineerService engineers;
    private final ReSkillMatrixService matrix;
    private final ReSkillImportService importer;
    private final ReModelMappingService mappings;
    private final ReManagerGrantRepository grants;
    private final UserRepository users;
    private final ReEventLog events;

    @GetMapping("/access")
    public ApiResponse<AccessView> access(@AuthenticationPrincipal UserPrincipal me) {
        return ApiResponse.success(new AccessView(access.canManage(me), access.isAdmin(me)));
    }

    /* ─── Queue ───────────────────────────────────────────────────────────── */

    @GetMapping("/queue")
    public ApiResponse<QueueView> queue(@AuthenticationPrincipal UserPrincipal me) {
        return ApiResponse.success(queue.queue(access.canManage(me)));
    }

    /** Read the open tickets from monday now. About 7 monday calls; a few seconds. */
    @PostMapping("/refresh")
    public ApiResponse<RefreshResult> refresh(@AuthenticationPrincipal UserPrincipal me) {
        access.requireManage(me);
        return ApiResponse.success(refresh.refresh());
    }

    @PostMapping("/assignments")
    public ApiResponse<AssignmentView> approve(@Valid @RequestBody ApproveRequest req,
                                               @AuthenticationPrincipal UserPrincipal me) {
        access.requireManage(me);
        String actor = ReAccessService.actor(me);
        ReAssignment a = assignmentService.approve(req, actor);
        // After the approval commits: a mail failure must not roll the decision back.
        a = assignmentService.notifyEngineer(a.getId(), actor);
        return ApiResponse.success(view(a));
    }

    @PostMapping("/assignments/{id}/cancel")
    public ApiResponse<AssignmentView> cancel(@PathVariable UUID id, @Valid @RequestBody ReasonRequest req,
                                              @AuthenticationPrincipal UserPrincipal me) {
        access.requireManage(me);
        return ApiResponse.success(view(assignmentService.cancel(id, req.reason(), ReAccessService.actor(me))));
    }

    @PostMapping("/assignments/{id}/email")
    public ApiResponse<AssignmentView> resend(@PathVariable UUID id, @AuthenticationPrincipal UserPrincipal me) {
        access.requireManage(me);
        return ApiResponse.success(view(assignmentService.notifyEngineer(id, ReAccessService.actor(me))));
    }

    @GetMapping("/assignments")
    public ApiResponse<List<AssignmentView>> history(@AuthenticationPrincipal UserPrincipal me) {
        access.requireManage(me);
        return ApiResponse.success(assignmentService.history());
    }

    @PostMapping("/holds")
    public ApiResponse<Void> hold(@Valid @RequestBody HoldRequest req, @AuthenticationPrincipal UserPrincipal me) {
        access.requireManage(me);
        assignmentService.hold(req, ReAccessService.actor(me));
        return ApiResponse.success("Held", null);
    }

    @DeleteMapping("/holds/{itemId}")
    public ApiResponse<Void> release(@PathVariable String itemId, @AuthenticationPrincipal UserPrincipal me) {
        access.requireManage(me);
        assignmentService.release(itemId, ReAccessService.actor(me));
        return ApiResponse.success("Released", null);
    }

    /* ─── Engineers ───────────────────────────────────────────────────────── */

    @GetMapping("/engineers")
    public ApiResponse<List<EngineerView>> engineers(@AuthenticationPrincipal UserPrincipal me) {
        access.requireManage(me);
        return ApiResponse.success(engineers.list());
    }

    @PostMapping("/engineers")
    public ApiResponse<UUID> createEngineer(@Valid @RequestBody EngineerRequest req,
                                            @AuthenticationPrincipal UserPrincipal me) {
        access.requireManage(me);
        ReEngineer e = engineers.create(req, ReAccessService.actor(me));
        return ApiResponse.success(e.getId());
    }

    @PutMapping("/engineers/{id}")
    public ApiResponse<UUID> updateEngineer(@PathVariable UUID id, @Valid @RequestBody EngineerRequest req,
                                            @AuthenticationPrincipal UserPrincipal me) {
        access.requireManage(me);
        return ApiResponse.success(engineers.update(id, req, ReAccessService.actor(me)).getId());
    }

    @GetMapping("/engineers/{id}/skill-history")
    public ApiResponse<List<SkillHistoryEntry>> skillHistory(@PathVariable UUID id,
                                                             @AuthenticationPrincipal UserPrincipal me) {
        access.requireManage(me);
        return ApiResponse.success(matrix.history(id));
    }

    @GetMapping("/monday-people")
    public ApiResponse<List<MondayPerson>> mondayPeople(@AuthenticationPrincipal UserPrincipal me) {
        access.requireManage(me);
        return ApiResponse.success(engineers.mondayPeople());
    }

    @GetMapping("/leave")
    public ApiResponse<List<LeaveView>> leave(@AuthenticationPrincipal UserPrincipal me) {
        access.requireManage(me);
        return ApiResponse.success(engineers.upcomingLeave());
    }

    @PostMapping("/leave")
    public ApiResponse<Void> addLeave(@Valid @RequestBody LeaveRequest req, @AuthenticationPrincipal UserPrincipal me) {
        access.requireManage(me);
        engineers.addLeave(req, ReAccessService.actor(me));
        return ApiResponse.success("Leave recorded", null);
    }

    @DeleteMapping("/leave/{id}")
    public ApiResponse<Void> deleteLeave(@PathVariable UUID id, @AuthenticationPrincipal UserPrincipal me) {
        access.requireManage(me);
        engineers.deleteLeave(id, ReAccessService.actor(me));
        return ApiResponse.success("Leave removed", null);
    }

    @GetMapping("/bookings")
    public ApiResponse<List<ScheduleView>> bookings(@AuthenticationPrincipal UserPrincipal me) {
        access.requireManage(me);
        return ApiResponse.success(engineers.upcomingBookings());
    }

    @PostMapping("/bookings")
    public ApiResponse<Void> addBooking(@Valid @RequestBody ScheduleRequest req, @AuthenticationPrincipal UserPrincipal me) {
        access.requireManage(me);
        engineers.addBooking(req, ReAccessService.actor(me));
        return ApiResponse.success("Booking recorded", null);
    }

    @DeleteMapping("/bookings/{id}")
    public ApiResponse<Void> deleteBooking(@PathVariable UUID id, @AuthenticationPrincipal UserPrincipal me) {
        access.requireManage(me);
        engineers.deleteBooking(id, ReAccessService.actor(me));
        return ApiResponse.success("Booking removed", null);
    }

    /* ─── Skill matrix ────────────────────────────────────────────────────── */

    @GetMapping("/skills")
    public ApiResponse<MatrixView> skills(@AuthenticationPrincipal UserPrincipal me) {
        access.requireManage(me);
        return ApiResponse.success(matrix.matrix());
    }

    @PutMapping("/skills")
    public ApiResponse<RevisionView> updateSkills(@Valid @RequestBody MatrixUpdateRequest req,
                                                  @AuthenticationPrincipal UserPrincipal me) {
        access.requireManage(me);
        return ApiResponse.success(matrix.update(req, ReAccessService.actor(me)));
    }

    @PostMapping(value = "/skills/import/preview", consumes = "multipart/form-data")
    public ApiResponse<ImportPreview> previewImport(@RequestParam("file") MultipartFile file,
                                                    @AuthenticationPrincipal UserPrincipal me) throws IOException {
        access.requireManage(me);
        return ApiResponse.success(importer.preview(requireXlsx(file)));
    }

    @PostMapping(value = "/skills/import", consumes = "multipart/form-data")
    public ApiResponse<ImportResult> commitImport(@RequestParam("file") MultipartFile file,
                                                  @RequestParam("label") String label,
                                                  @RequestParam("reason") String reason,
                                                  @AuthenticationPrincipal UserPrincipal me) throws IOException {
        access.requireManage(me);
        return ApiResponse.success(importer.commit(requireXlsx(file), label, reason, ReAccessService.actor(me)));
    }

    /* ─── Model mapping ───────────────────────────────────────────────────── */

    @GetMapping("/model-mappings")
    public ApiResponse<List<MappingView>> mappings(@AuthenticationPrincipal UserPrincipal me) {
        access.requireManage(me);
        return ApiResponse.success(mappings.list());
    }

    @PutMapping("/model-mappings")
    public ApiResponse<MappingView> saveMapping(@Valid @RequestBody MappingRequest req,
                                                @AuthenticationPrincipal UserPrincipal me) {
        access.requireManage(me);
        return ApiResponse.success(mappings.save(req, ReAccessService.actor(me)));
    }

    /* ─── Who may manage (ADMIN only) ─────────────────────────────────────── */

    @GetMapping("/managers")
    public ApiResponse<List<ManagerView>> managers(@AuthenticationPrincipal UserPrincipal me) {
        access.requireManage(me);
        List<ManagerView> out = new ArrayList<>();
        for (ReManagerGrant g : grants.findAll()) {
            users.findById(g.getUserId()).ifPresent(u -> out.add(new ManagerView(u.getId(), u.getEmail(),
                    u.getFullName(), u.getRole().name(), g.getGrantedBy(), g.getGrantedAt())));
        }
        return ApiResponse.success(out);
    }

    @PostMapping("/managers")
    public ApiResponse<Void> grant(@Valid @RequestBody GrantRequest req, @AuthenticationPrincipal UserPrincipal me) {
        access.requireAdmin(me);
        User u = users.findByEmailIgnoreCaseAndIsActiveTrue(req.email().trim())
                .orElseThrow(() -> new BadRequestException("No active user with email " + req.email()));
        grants.save(ReManagerGrant.builder().userId(u.getId()).grantedBy(ReAccessService.actor(me)).build());
        events.record("MANAGER", u.getId(), "GRANTED", ReAccessService.actor(me), Map.of("email", u.getEmail()));
        return ApiResponse.success("Granted", null);
    }

    @DeleteMapping("/managers/{userId}")
    public ApiResponse<Void> revoke(@PathVariable UUID userId, @AuthenticationPrincipal UserPrincipal me) {
        access.requireAdmin(me);
        grants.deleteById(userId);
        events.record("MANAGER", userId, "REVOKED", ReAccessService.actor(me), Map.of());
        return ApiResponse.success("Revoked", null);
    }

    private AssignmentView view(ReAssignment a) {
        return assignmentService.history().stream().filter(v -> v.id().equals(a.getId())).findFirst()
                .orElse(new AssignmentView(a.getId(), a.getItemId(), null, a.getEngineerId(), null, a.getStatus(),
                        a.getOrigin(), a.getScore(), null, a.getReason(), a.getApprovedBy(), a.getApprovedAt(),
                        a.getConfirmedAt(), a.getEndedAt(), a.getEndedBy(), a.getEmailStatus(), a.getEmailDetail(),
                        a.getMondayStatus(), a.getMondayDetail()));
    }

    private static byte[] requireXlsx(MultipartFile file) throws IOException {
        String name = file.getOriginalFilename() == null ? "" : file.getOriginalFilename().toLowerCase(Locale.ROOT);
        if (!name.endsWith(".xlsx")) throw new BadRequestException("Upload the skill matrix as an .xlsx file");
        return file.getBytes();
    }
}
