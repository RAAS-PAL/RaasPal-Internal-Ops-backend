package com.raaspal.robotrecommendation.auth.dto;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;

public record LoginRequest(
        @Email(message = "invalid")
        @NotBlank(message = "invalid")
        String email,

        @NotBlank(message = "credentials")
        String password,

        String audience
) {
    public LoginRequest(String email, String password) { this(email, password, null); }
    @Override public String toString() { return "LoginRequest[redacted]"; }
}
