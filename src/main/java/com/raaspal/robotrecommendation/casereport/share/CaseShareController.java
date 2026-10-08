package com.raaspal.robotrecommendation.casereport.share;

import com.raaspal.robotrecommendation.auth.security.UserPrincipal;
import com.raaspal.robotrecommendation.common.response.ApiResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.UUID;

/**
 * Public links to pending cases: a details page, a tab for a period, or one case.
 *
 * <p>Sharing, listing, extending and stopping are for the RE team and admins
 * (SecurityConfig, by URL, as for anything that reaches a customer). Opening a link is
 * public: {@code /api/v1/reports/public/**} needs no sign-in, and the token is the access.
 */
@RestController
@RequiredArgsConstructor
public class CaseShareController {

    private final CaseShareService shares;

    @PostMapping("/api/v1/case-reports/share-links")
    public ApiResponse<CaseShareService.LinkView> create(@RequestBody CaseShareService.CreateRequest request,
                                                         @AuthenticationPrincipal UserPrincipal me) {
        return ApiResponse.success(shares.create(request, me == null ? null : me.getId()));
    }

    /**
     * The links still working for one details page ({@code kind=SHEET&sheet=mk}), one tab
     * ({@code kind=VIEW&view=pcs}, every period) or one case
     * ({@code kind=CASE&sheet=mk&caseKey=…}), newest first.
     */
    @GetMapping("/api/v1/case-reports/share-links")
    public ApiResponse<List<CaseShareService.LinkView>> active(@RequestParam String kind,
                                                               @RequestParam(required = false) String sheet,
                                                               @RequestParam(required = false) String view,
                                                               @RequestParam(required = false) String caseKey) {
        return ApiResponse.success(shares.active(kind, sheet, view, caseKey));
    }

    /**
     * Every link, newest first, for the links page: {@code status=ACTIVE}, {@code EXPIRED} or
     * {@code STOPPED}, or every one when left out.
     */
    @GetMapping("/api/v1/case-reports/share-links/all")
    public ApiResponse<List<CaseShareService.LinkView>> all(@RequestParam(required = false) String status) {
        return ApiResponse.success(shares.all(status));
    }

    /** Moves a link's end to {@code days} from now. */
    @PutMapping("/api/v1/case-reports/share-links/{id}/expiry")
    public ApiResponse<CaseShareService.LinkView> extend(@PathVariable UUID id,
                                                         @RequestBody CaseShareService.ExpiryRequest request) {
        return ApiResponse.success(shares.extend(id, request.days()));
    }

    /** Stop sharing: the link stops working at once. */
    @DeleteMapping("/api/v1/case-reports/share-links/{id}")
    public ApiResponse<Void> stop(@PathVariable UUID id) {
        shares.stop(id);
        return ApiResponse.success("Stopped sharing");
    }

    /**
     * What a link shows, for anyone who has it. Not cached anywhere on the way, and not
     * for search engines: it is current cases, and it is only for whom it was sent to.
     */
    @GetMapping("/api/v1/reports/public/cases/{token}")
    public ResponseEntity<ApiResponse<CaseShareService.PublicView>> open(@PathVariable String token) {
        return ResponseEntity.ok()
                .cacheControl(CacheControl.noStore())
                .header("X-Robots-Tag", "noindex, nofollow")
                .body(ApiResponse.success(shares.open(token)));
    }
}
