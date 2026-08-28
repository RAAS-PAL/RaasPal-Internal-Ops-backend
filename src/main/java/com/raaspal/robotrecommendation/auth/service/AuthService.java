package com.raaspal.robotrecommendation.auth.service;

import com.raaspal.robotrecommendation.auth.dto.AuthResponse;
import com.raaspal.robotrecommendation.auth.dto.ChangePasswordRequest;
import com.raaspal.robotrecommendation.auth.dto.LoginRequest;
import com.raaspal.robotrecommendation.auth.dto.VerifyPasswordRequest;
import com.raaspal.robotrecommendation.auth.security.jwt.JwtUtils;
import com.raaspal.robotrecommendation.common.exception.BadRequestException;
import com.raaspal.robotrecommendation.user.dto.UserResponse;
import com.raaspal.robotrecommendation.user.entity.User;
import com.raaspal.robotrecommendation.user.repository.UserRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class AuthService {

    private final UserRepository userRepository;
    private final PasswordEncoder passwordEncoder;
    private final JwtUtils jwtUtils;

    public void verifyPassword(String email, VerifyPasswordRequest request) {
        User user = userRepository.findByEmailIgnoreCaseAndIsActiveTrue(email)
                .orElseThrow(() -> new IllegalArgumentException("User not found"));
        if (!passwordEncoder.matches(request.password(), user.getPassword())) {
            throw new IllegalArgumentException("Incorrect password");
        }
    }

    /**
     * Replaces the caller's own password, after checking the current one.
     * <p>
     * {@code email} comes from the authenticated principal, never the request body,
     * so this cannot be aimed at another account.
     * <p>
     * ⚠️ Sessions elsewhere survive this. JWTs are stateless and carry no reference
     * to the stored hash, so any token already issued stays valid until it expires.
     * Changing a password therefore does not evict someone who already has one —
     * closing that needs a token version or a deny list, which the platform has no
     * need for yet but would need before this is treated as a way to lock an
     * intruder out.
     */
    @Transactional
    public void changePassword(String email, ChangePasswordRequest request) {
        User user = userRepository.findByEmailIgnoreCaseAndIsActiveTrue(email)
                .orElseThrow(() -> new IllegalArgumentException("User not found"));

        if (!passwordEncoder.matches(request.currentPassword(), user.getPassword())) {
            // Deliberately not "wrong current password" versus anything else: the
            // caller is already authenticated, so there is nothing to enumerate, but
            // a single clear message is also all that is useful to them.
            throw new BadRequestException("Your current password is incorrect");
        }
        if (passwordEncoder.matches(request.newPassword(), user.getPassword())) {
            throw new BadRequestException("The new password must be different from the current one");
        }

        user.setPassword(passwordEncoder.encode(request.newPassword()));
        userRepository.save(user);
    }

    public AuthResponse login(LoginRequest request) {
        User user = userRepository.findByEmailIgnoreCaseAndIsActiveTrue(request.email())
                .orElseThrow(() -> new IllegalArgumentException("Invalid email or password"));

        if (!passwordEncoder.matches(request.password(), user.getPassword())) {
            throw new IllegalArgumentException("Invalid email or password");
        }

        String token = jwtUtils.generateToken(user.getEmail());
        return new AuthResponse(token, UserResponse.from(user));
    }
}
