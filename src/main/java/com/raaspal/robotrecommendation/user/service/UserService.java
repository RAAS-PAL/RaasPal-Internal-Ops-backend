package com.raaspal.robotrecommendation.user.service;

import com.raaspal.robotrecommendation.common.exception.ResourceNotFoundException;
import com.raaspal.robotrecommendation.user.dto.CreateUserRequest;
import com.raaspal.robotrecommendation.user.dto.UserResponse;
import com.raaspal.robotrecommendation.user.entity.User;
import com.raaspal.robotrecommendation.user.repository.UserRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.UUID;

@Service
@RequiredArgsConstructor
public class UserService {

    private final UserRepository userRepository;
    private final PasswordEncoder passwordEncoder;

    @Transactional(readOnly = true)
    public User getEntity(UUID id) {
        return userRepository.findById(id)
                .orElseThrow(() -> new ResourceNotFoundException("User", "id", id));
    }

    @Transactional(readOnly = true)
    public User getActiveEntityByEmail(String email) {
        return userRepository.findByEmailAndIsActiveTrue(email)
                .orElseThrow(() -> new ResourceNotFoundException("User", "email", email));
    }

    @Transactional(readOnly = true)
    public UserResponse getById(UUID id) {
        return UserResponse.from(getEntity(id));
    }

    @Transactional(readOnly = true)
    public Page<UserResponse> getAll(Pageable pageable) {
        return userRepository.findAll(pageable).map(UserResponse::from);
    }

    @Transactional
    public UserResponse create(CreateUserRequest request) {
        if (userRepository.existsByEmail(request.email())) {
            throw new IllegalArgumentException("Email already registered: " + request.email());
        }
        User user = User.builder()
                .email(request.email())
                .password(passwordEncoder.encode(request.password()))
                .fullName(request.fullName())
                .role(request.role())
                .build();
        return UserResponse.from(userRepository.save(user));
    }
}
