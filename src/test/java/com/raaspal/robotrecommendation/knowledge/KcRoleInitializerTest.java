package com.raaspal.robotrecommendation.knowledge;

import com.raaspal.robotrecommendation.auth.dto.LoginRequest;
import com.raaspal.robotrecommendation.auth.security.jwt.JwtUtils;
import com.raaspal.robotrecommendation.auth.service.AuthService;
import com.raaspal.robotrecommendation.common.enums.Role;
import com.raaspal.robotrecommendation.user.entity.User;
import com.raaspal.robotrecommendation.user.repository.UserRepository;
import jakarta.persistence.EntityManager;
import jakarta.persistence.LockModeType;
import org.junit.jupiter.api.Test;
import org.springframework.security.crypto.password.PasswordEncoder;
import java.util.Optional;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

class KcRoleInitializerTest {
    final EntityManager em = mock(EntityManager.class);
    final KcRoleInitializer roles = new KcRoleInitializer(em);
    final User user = User.builder().email("kc-role-test@raaspal.com").password("old-hash")
            .fullName("Role test").role(Role.RAASPAL_TEAM).build();

    @Test void establishedRoleDoesNotLockOrReload() {
        user.setKcRole(KcRole.EDITOR);
        roles.initialize(user);
        verifyNoInteractions(em);
        assertThat(user.getKcRole()).isEqualTo(KcRole.EDITOR);
    }

    @Test void firstUseRefreshesUnderLockBeforeInitializing() {
        roles.initialize(user);
        verify(em).refresh(user, LockModeType.PESSIMISTIC_WRITE);
        assertThat(user.getKcRole()).isEqualTo(KcRole.VIEWER);
        assertThat(user.getRole()).isEqualTo(Role.RAASPAL_TEAM);
    }

    @Test void concurrentlyAssignedRoleIsPreserved() {
        doAnswer(call -> { user.setKcRole(KcRole.ADMIN); return null; })
                .when(em).refresh(user, LockModeType.PESSIMISTIC_WRITE);
        roles.initialize(user);
        assertThat(user.getKcRole()).isEqualTo(KcRole.ADMIN);
    }

    @Test void concurrentDeactivationRefusesInitialization() {
        doAnswer(call -> { user.setActive(false); return null; })
                .when(em).refresh(user, LockModeType.PESSIMISTIC_WRITE);
        assertThatThrownBy(() -> roles.initialize(user)).isInstanceOf(KcAuthException.class).hasMessage("credentials");
        assertThat(user.getKcRole()).isNull();
    }

    @Test void legacyAndEstablishedKcLoginsUseUnlockedLookups() {
        var users = mock(UserRepository.class);
        var passwords = mock(PasswordEncoder.class);
        var jwt = mock(JwtUtils.class);
        var auth = new AuthService(users, passwords, jwt, roles);
        when(users.findByEmailIgnoreCaseAndIsActiveTrue(user.getEmail())).thenReturn(Optional.of(user));
        when(passwords.matches("test-password", user.getPassword())).thenReturn(true);
        auth.login(new LoginRequest(user.getEmail(), "test-password"));
        user.setKcRole(KcRole.VIEWER);
        auth.login(new LoginRequest(user.getEmail(), "test-password", "kc"));
        verify(users, never()).lockByEmail(anyString());
        verifyNoInteractions(em);
        verify(jwt, times(2)).generateToken(user.getEmail());
    }

    @Test void loginRechecksPasswordAfterLockedRefresh() {
        var users = mock(UserRepository.class);
        var passwords = mock(PasswordEncoder.class);
        var jwt = mock(JwtUtils.class);
        var auth = new AuthService(users, passwords, jwt, roles);
        when(users.findByEmailIgnoreCaseAndIsActiveTrue(user.getEmail())).thenReturn(Optional.of(user));
        when(passwords.matches("test-password", "old-hash")).thenReturn(true);
        when(passwords.matches("test-password", "new-hash")).thenReturn(false);
        doAnswer(call -> { user.setPassword("new-hash"); return null; })
                .when(em).refresh(user, LockModeType.PESSIMISTIC_WRITE);
        assertThatThrownBy(() -> auth.login(new LoginRequest(user.getEmail(), "test-password", "kc")))
                .isInstanceOf(KcAuthException.class).hasMessage("credentials");
        verifyNoInteractions(jwt);
    }
}
