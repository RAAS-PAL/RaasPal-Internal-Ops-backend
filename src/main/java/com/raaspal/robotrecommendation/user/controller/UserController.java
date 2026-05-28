package com.raaspal.robotrecommendation.user.controller;

import com.raaspal.robotrecommendation.common.response.ApiResponse;
import com.raaspal.robotrecommendation.common.response.PagedResponse;
import com.raaspal.robotrecommendation.user.dto.CreateUserRequest;
import com.raaspal.robotrecommendation.user.dto.UserResponse;
import com.raaspal.robotrecommendation.user.service.UserService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Pageable;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.UUID;

@RestController
@RequestMapping("/api/v1/users")
@RequiredArgsConstructor
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
}
