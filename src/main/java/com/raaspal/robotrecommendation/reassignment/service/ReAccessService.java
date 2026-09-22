package com.raaspal.robotrecommendation.reassignment.service;

import com.raaspal.robotrecommendation.auth.security.UserPrincipal;
import com.raaspal.robotrecommendation.reassignment.repository.ReManagerGrantRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

/**
 * Who may do what in the RE module.
 *
 * <p>Viewing the queue is open to ADMIN and RAASPAL_TEAM (enforced on the controller).
 * Managing - editing engineers and skill levels, approving assignments - needs ADMIN or an
 * explicit grant: the skill matrix is a personal assessment of named employees, so the
 * broad team role alone is not enough.
 */
@Service
@RequiredArgsConstructor
public class ReAccessService {

    private final ReManagerGrantRepository grants;

    public boolean isAdmin(UserPrincipal principal) {
        return principal != null && principal.getAuthorities().stream()
                .anyMatch(a -> "ROLE_ADMIN".equals(a.getAuthority()));
    }

    public boolean canManage(UserPrincipal principal) {
        return principal != null && (isAdmin(principal) || grants.existsById(principal.getId()));
    }

    public void requireManage(UserPrincipal principal) {
        if (!canManage(principal)) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN,
                    "Managing RE assignment needs ADMIN or a Senior RE grant");
        }
    }

    public void requireAdmin(UserPrincipal principal) {
        if (!isAdmin(principal)) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "Only an ADMIN can change who manages RE assignment");
        }
    }

    /** What the audit trail records as the actor. */
    public static String actor(UserPrincipal principal) {
        return principal == null ? "system" : principal.getUsername();
    }
}
