package com.raaspal.robotrecommendation.auth.controller;

import com.raaspal.robotrecommendation.auth.dto.AuthResponse;
import com.raaspal.robotrecommendation.auth.dto.LoginRequest;
import com.raaspal.robotrecommendation.auth.service.AuthService;
import com.raaspal.robotrecommendation.common.response.ApiResponse;
import com.raaspal.robotrecommendation.user.dto.UserResponse;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/auth")
@RequiredArgsConstructor
public class AuthController {

    private final AuthService authService;

    @PostMapping("/login")
    public ApiResponse<AuthResponse> login(@Valid @RequestBody LoginRequest request) {
        UserResponse user = authService.findActiveUser(request);
        return ApiResponse.success("Login accepted", new AuthResponse("jwt-not-configured-yet", user));
    }

    @PostMapping("/logout")
    public ApiResponse<Void> logout() {
        return ApiResponse.success("Logged out");
    }

    @GetMapping("/me")
    public ApiResponse<UserResponse> me(@RequestParam String email) {
        return ApiResponse.success(authService.findActiveUser(new LoginRequest(email, "not-used")));
    }
}
