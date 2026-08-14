package com.raaspal.robotrecommendation.user.controller;

import com.raaspal.robotrecommendation.common.response.ApiResponse;
import com.raaspal.robotrecommendation.common.response.PagedResponse;
import com.raaspal.robotrecommendation.user.dto.CreateUserRequest;
import com.raaspal.robotrecommendation.user.dto.UpdateUserRequest;
import com.raaspal.robotrecommendation.user.dto.UserResponse;
import com.raaspal.robotrecommendation.user.service.UserService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Pageable;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.UUID;

/**
 * Account administration. <strong>ADMIN only, on every method.</strong>
 *
 * <p>Until now these endpoints were merely "authenticated", which was harmless while
 * the whole team shared one login and stops being harmless the moment accounts differ
 * in privilege: any staff member could POST here with their own token and mint
 * themselves an ADMIN account. Hiding the button in the UI does not help — the token
 * is in the browser, and curl does not read React.
 *
 * <p>Listing users is restricted too, not just creating them. A staff directory with
 * email addresses and roles is a reconnaissance aid, and nothing outside account
 * administration needs it — the signed-in user's own details come from
 * {@code GET /api/v1/auth/me}.
 */
@RestController
@RequestMapping("/api/v1/users")
@RequiredArgsConstructor
@PreAuthorize("hasRole('ADMIN')")
public class UserController {

    private final UserService userService;

    @GetMapping
    public ApiResponse<PagedResponse<UserResponse>> getAll(Pageable pageable) {
        return ApiResponse.success(PagedResponse.of(userService.getAll(pageable)));
    }

    @GetMapping("/{id}")
    public ApiResponse<UserResponse> getById(@PathVariable UUID id) {
        return ApiResponse.success(userService.getById(id));
    }

    @PostMapping
    public ApiResponse<UserResponse> create(@Valid @RequestBody CreateUserRequest request) {
        return ApiResponse.success("User created", userService.create(request));
    }

    /**
     * Change a role, a name, or whether the account can sign in.
     *
     * <p>Deactivating is the way to remove someone: deleting the row would orphan
     * every {@code stock_movements.created_by} that points at them and silently
     * hollow out the audit trail.
     */
    @PatchMapping("/{id}")
    public ApiResponse<UserResponse> update(@PathVariable UUID id,
                                            @Valid @RequestBody UpdateUserRequest request) {
        return ApiResponse.success("User updated", userService.update(id, request));
    }
}
