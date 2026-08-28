package com.raaspal.robotrecommendation.auth.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * Change your own password.
 *
 * <p>Note what is absent: any way to say <em>whose</em> password. The account is
 * taken from the authenticated principal, so this endpoint cannot be pointed at
 * someone else no matter what the caller sends.
 *
 * <p>{@code currentPassword} is required even though the caller already holds a
 * valid token. A token proves the session was authenticated at some point, not that
 * the person holding the keyboard right now is the account owner — an unattended
 * desk or a borrowed laptop is exactly the case this guards.
 */
public record ChangePasswordRequest(

        @NotBlank(message = "Enter your current password")
        String currentPassword,

        /** Same floor as account creation; a shorter one here would be a back door. */
        @NotBlank(message = "Enter a new password")
        @Size(min = 8, message = "New password must be at least 8 characters")
        String newPassword
) {
}
