package com.raaspal.robotrecommendation.auth.service;

import com.raaspal.robotrecommendation.auth.dto.LoginRequest;
import com.raaspal.robotrecommendation.common.exception.ResourceNotFoundException;
import com.raaspal.robotrecommendation.user.dto.UserResponse;
import com.raaspal.robotrecommendation.user.entity.User;
import com.raaspal.robotrecommendation.user.repository.UserRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class AuthService {

    private final UserRepository userRepository;

    public UserResponse findActiveUser(LoginRequest request) {
        User user = userRepository.findByEmailAndIsActiveTrue(request.email())
                .orElseThrow(() -> new ResourceNotFoundException("User", "email", request.email()));

        return UserResponse.from(user);
    }
}
