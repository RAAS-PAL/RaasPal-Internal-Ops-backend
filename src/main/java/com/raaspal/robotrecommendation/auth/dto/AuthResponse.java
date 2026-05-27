package com.raaspal.robotrecommendation.auth.dto;

import com.raaspal.robotrecommendation.user.dto.UserResponse;

public record AuthResponse(
        String accessToken,
        String tokenType,
        UserResponse user
) {
    public AuthResponse(String accessToken, UserResponse user) {
        this(accessToken, "Bearer", user);
    }
}
