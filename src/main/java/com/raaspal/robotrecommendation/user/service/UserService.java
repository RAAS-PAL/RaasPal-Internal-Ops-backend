package com.raaspal.robotrecommendation.user.service;

import com.raaspal.robotrecommendation.common.enums.Role;
import com.raaspal.robotrecommendation.common.exception.BadRequestException;
import com.raaspal.robotrecommendation.common.exception.ResourceNotFoundException;
import com.raaspal.robotrecommendation.user.dto.CreateUserRequest;
import com.raaspal.robotrecommendation.user.dto.UpdateUserRequest;
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
        return userRepository.findByEmailIgnoreCaseAndIsActiveTrue(email)
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

    /**
     * Change a role, a name, or whether an account can sign in.
     *
     * <p>Guards against locking everyone out: the last active ADMIN cannot be demoted
     * or disabled. Without this an administrator can, in two clicks and with no
     * warning, leave the platform with nobody able to manage accounts — recoverable
     * only by editing the database by hand.
     */
    @Transactional
    public UserResponse update(UUID id, UpdateUserRequest request) {
        User user = getEntity(id);

        boolean losesAdmin = user.getRole() == Role.ADMIN
                && ((request.role() != null && request.role() != Role.ADMIN)
                    || Boolean.FALSE.equals(request.active()));

        if (losesAdmin && userRepository.countByRoleAndIsActiveTrue(Role.ADMIN) <= 1) {
            throw new BadRequestException(
                    "This is the last active administrator. Promote another account first.");
        }

        if (request.fullName() != null && !request.fullName().isBlank()) {
            user.setFullName(request.fullName().trim());
        }
        if (request.role() != null)   user.setRole(request.role());
        if (request.active() != null) user.setActive(request.active());

        return UserResponse.from(userRepository.save(user));
    }

    @Transactional
    public UserResponse create(CreateUserRequest request) {
        // Stored lower-cased so the column holds one canonical form. Lookups ignore
        // case anyway, but normalising on the way in means the value a person reads
        // in the database is the value they would type — and it keeps a UNIQUE index
        // on email meaningful, since "A@x.com" and "a@x.com" are the same mailbox.
        String email = request.email().trim().toLowerCase();

        if (userRepository.existsByEmailIgnoreCase(email)) {
            throw new IllegalArgumentException("Email already registered: " + email);
        }
        User user = User.builder()
                .email(email)
                .password(passwordEncoder.encode(request.password()))
                .fullName(request.fullName())
                .role(request.role())
                .build();
        return UserResponse.from(userRepository.save(user));
    }
}
