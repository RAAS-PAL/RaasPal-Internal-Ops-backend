package com.raaspal.robotrecommendation.auth.controller;

import com.raaspal.robotrecommendation.auth.dto.AuthResponse;
import com.raaspal.robotrecommendation.auth.dto.LoginRequest;
import com.raaspal.robotrecommendation.auth.dto.VerifyPasswordRequest;
import com.raaspal.robotrecommendation.auth.security.UserPrincipal;
import com.raaspal.robotrecommendation.auth.service.AuthService;
import com.raaspal.robotrecommendation.common.response.ApiResponse;
import com.raaspal.robotrecommendation.user.dto.UserResponse;
import com.raaspal.robotrecommendation.user.service.UserService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/auth")
@RequiredArgsConstructor
public class AuthController {

    private final AuthService authService;
    private final UserService userService;

    @PostMapping("/login")
    public ApiResponse<AuthResponse> login(@Valid @RequestBody LoginRequest request) {
        return ApiResponse.success("Login successful", authService.login(request));
    }

    @PostMapping("/verify-password")
    public ApiResponse<Void> verifyPassword(
            @AuthenticationPrincipal UserPrincipal principal,
            @Valid @RequestBody VerifyPasswordRequest request) {
        authService.verifyPassword(principal.getUsername(), request);
        return ApiResponse.success("Password verified");
    }

    @PostMapping("/logout")
    public ApiResponse<Void> logout() {
        // JWT is stateless — the client simply discards the token.
        return ApiResponse.success("Logged out");
    }

    @GetMapping("/me")
    public ApiResponse<UserResponse> me(@AuthenticationPrincipal UserPrincipal principal) {
        return ApiResponse.success(userService.getById(principal.getId()));
    }
}
