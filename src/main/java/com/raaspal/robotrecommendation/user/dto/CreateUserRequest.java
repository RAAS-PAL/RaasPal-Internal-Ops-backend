package com.raaspal.robotrecommendation.user.dto;

import com.raaspal.robotrecommendation.common.enums.Role;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

public record CreateUserRequest(

        @Email
        @NotBlank
        String email,

        @NotBlank
        @Size(min = 8, message = "Password must be at least 8 characters")
        String password,

        @NotBlank
        @Size(max = 255)
        String fullName,

        @NotNull
        Role role
) {
}
