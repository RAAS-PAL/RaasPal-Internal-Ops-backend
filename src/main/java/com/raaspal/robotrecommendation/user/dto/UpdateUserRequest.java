package com.raaspal.robotrecommendation.user.dto;

import com.raaspal.robotrecommendation.common.enums.Role;
import jakarta.validation.constraints.Size;

/**
 * Change an account's role, name, or whether it can sign in.
 *
 * <p>Every field is optional; null means "leave alone". Email is absent and
 * immutable — it is the login identity, and letting it move would silently
 * reassign whoever is holding a live session.
 *
 * <p>Password is absent too. Resetting someone else's password is a different
 * operation with different consequences from renaming them, and folding it into a
 * general edit makes it far too easy to do by accident.
 */
public record UpdateUserRequest(

        @Size(max = 255) String fullName,

        Role role,

        /** false disables sign-in without deleting the account or its audit trail. */
        Boolean active
) {
}
