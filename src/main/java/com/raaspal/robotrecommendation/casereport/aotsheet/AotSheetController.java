package com.raaspal.robotrecommendation.casereport.aotsheet;

import com.raaspal.robotrecommendation.auth.security.UserPrincipal;
import com.raaspal.robotrecommendation.casereport.service.CaseTicketSyncService.SyncResult;
import com.raaspal.robotrecommendation.common.response.ApiResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.Duration;

/**
 * The AOT Google Sheet: where it is, how it reads, and syncing it by hand.
 *
 * <p>The daily run syncs it too, through {@code CaseSyncCoordinator}, once the sync is
 * switched on in the AOT tab of Pending cases; the 15-minute refresh and opening the AOT tab
 * keep it current in between ({@link AotSheetAutoSync}).
 */
@RestController
@RequestMapping("/api/v1/case-reports/aot-sheet")
@RequiredArgsConstructor
public class AotSheetController {

    /** How stale the sheet may be before opening the AOT tab syncs it again. */
    private static final Duration ON_OPEN_MIN_AGE = Duration.ofMinutes(5);

    private final AotSheetSyncService service;
    private final AotSheetSettingsService settings;
    private final AotSheetAutoSync autoSync;

    /** The saved link and column choices, and whether a key is configured to use them. */
    @GetMapping("/settings")
    public ApiResponse<AotSheetSettingsService.SettingsView> settings() {
        return ApiResponse.success(settings.view());
    }

    /** Changes which sheet is read and how. Staff only: it decides what AOT's report shows. */
    @PutMapping("/settings")
    @PreAuthorize("hasAnyRole('ADMIN','RAASPAL_TEAM')")
    public ApiResponse<AotSheetSettingsService.SettingsView> saveSettings(
            @RequestBody AotSheetSettingsService.SettingsRequest request,
            @AuthenticationPrincipal UserPrincipal me) {
        return ApiResponse.success("AOT sheet settings saved",
                settings.save(request, me == null ? null : me.getUsername()));
    }

    /** Unlinks the sheet until a link is saved again. */
    @DeleteMapping("/settings")
    @PreAuthorize("hasAnyRole('ADMIN','RAASPAL_TEAM')")
    public ApiResponse<AotSheetSettingsService.SettingsView> removeSettings() {
        return ApiResponse.success("AOT sheet unlinked", settings.remove());
    }

    /**
     * Brings the sheet up to date if it is due: the AOT tab calls this when it is opened.
     * Any signed-in user may, because it can only ever sync at most every few minutes and
     * never changes the settings.
     */
    @PostMapping("/refresh")
    public ApiResponse<AotSheetAutoSync.Outcome> refresh() {
        return ApiResponse.success(autoSync.syncIfDue(ON_OPEN_MIN_AGE));
    }

    /** The linked sheet's open cases as last synced. No call to Google. */
    @GetMapping("/cases")
    public ApiResponse<AotSheetOpenCases> openCases() {
        return ApiResponse.success(service.openCases());
    }

    /** Read-only: the sheet's headers, how each row maps, and what blocks a sync. */
    @GetMapping("/preview")
    public ApiResponse<AotSheetPreview> preview(@RequestParam(defaultValue = "5") int sample) {
        return ApiResponse.success(service.preview(Math.min(Math.max(sample, 0), 50)));
    }

    @PostMapping("/sync")
    @PreAuthorize("hasAnyRole('ADMIN','RAASPAL_TEAM')")
    public ApiResponse<SyncResult> sync() {
        return ApiResponse.success("AOT sheet synced", service.sync());
    }
}
