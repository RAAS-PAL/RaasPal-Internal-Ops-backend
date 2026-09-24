package com.raaspal.robotrecommendation.mkstock.controller;

import com.raaspal.robotrecommendation.common.response.ApiResponse;
import com.raaspal.robotrecommendation.mkstock.dto.MkDtos.*;
import com.raaspal.robotrecommendation.mkstock.service.MkAccessService;
import com.raaspal.robotrecommendation.mkstock.service.MkStockService;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

/**
 * MK staff's read-only view: {@code /api/v1/public/mk-stock}. Open at the security layer
 * (no staff login) - instead every read needs the view token a correct PIN returns, sent as
 * {@code X-MK-View-Token}. Nothing here can change stock, and who recorded a movement is
 * left out.
 *
 * <p>RIMS calls this from its server, so it forwards the viewer's own address in
 * {@code X-MK-Client} for the per-client PIN lockout; the global lockout does not depend on it.
 */
@RestController
@RequestMapping("/api/v1/public/mk-stock")
@RequiredArgsConstructor
public class MkViewController {

    static final String TOKEN_HEADER = "X-MK-View-Token";
    static final String CLIENT_HEADER = "X-MK-Client";

    private final MkStockService stock;
    private final MkAccessService access;

    @PostMapping("/session")
    public ApiResponse<ViewSession> login(@Valid @RequestBody ViewLoginRequest req, HttpServletRequest http) {
        String client = http.getHeader(CLIENT_HEADER);
        return ApiResponse.success(access.login(req.pin(), client == null ? http.getRemoteAddr() : client));
    }

    @DeleteMapping("/session")
    public ApiResponse<Void> logout(@RequestHeader(value = TOKEN_HEADER, required = false) String token) {
        access.logout(token);
        return ApiResponse.success("Signed out");
    }

    @GetMapping("/parts")
    public ApiResponse<List<PartView>> parts(@RequestHeader(value = TOKEN_HEADER, required = false) String token) {
        access.requireSession(token);
        return ApiResponse.success(stock.listParts(false));
    }

    @GetMapping("/parts/{id}/movements")
    public ApiResponse<List<MovementView>> history(@PathVariable UUID id,
                                                   @RequestHeader(value = TOKEN_HEADER, required = false) String token) {
        access.requireSession(token);
        return ApiResponse.success(stock.partHistory(id, true));
    }

    @GetMapping("/dashboard")
    public ApiResponse<Dashboard> dashboard(
            @RequestHeader(value = TOKEN_HEADER, required = false) String token,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to) {
        access.requireSession(token);
        return ApiResponse.success(stock.dashboard(from, to, true));
    }

    @ExceptionHandler(MkAccessService.Denied.class)
    public ResponseEntity<ApiResponse<Void>> denied(MkAccessService.Denied e) {
        return ResponseEntity.status(e.tooMany() ? HttpStatus.TOO_MANY_REQUESTS : HttpStatus.UNAUTHORIZED)
                .body(ApiResponse.error(e.getMessage()));
    }
}
