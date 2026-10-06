package com.raaspal.robotrecommendation.knowledge;

import com.raaspal.robotrecommendation.user.entity.User;
import jakarta.persistence.EntityManager;
import jakarta.persistence.EntityNotFoundException;
import jakarta.persistence.LockModeType;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
public class KcRoleInitializer {
    private final EntityManager em;

    @Transactional(propagation = Propagation.MANDATORY, noRollbackFor = KcAuthException.class)
    public void initialize(User user) {
        if (user.getKcRole() != null) return;
        // Refresh under the lock: a second repository query could return the stale
        // first-level cached entity and overwrite a concurrently assigned EDITOR/ADMIN.
        try { em.refresh(user, LockModeType.PESSIMISTIC_WRITE); }
        catch (EntityNotFoundException ex) { throw new KcAuthException("credentials", 401, 0); }
        if (!user.isActive()) throw new KcAuthException("credentials", 401, 0);
        try {
            if (user.getEmail() == null || !user.getEmail().contains("@")) throw new KcAuthException("domain");
            KcAuthService.companyEmail(user.getEmail());
        }
        catch (KcAuthException ex) { throw new KcAuthException("domain", 403, 0); }
        if (user.getKcRole() == null) user.setKcRole(KcRole.VIEWER);
    }
}
