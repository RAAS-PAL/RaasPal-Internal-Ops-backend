package com.raaspal.robotrecommendation.user;

import com.raaspal.robotrecommendation.common.enums.Role;
import com.raaspal.robotrecommendation.common.exception.BadRequestException;
import com.raaspal.robotrecommendation.user.entity.User;
import com.raaspal.robotrecommendation.user.repository.UserRepository;
import com.raaspal.robotrecommendation.user.service.UserService;
import org.junit.jupiter.api.Test;
import org.springframework.security.crypto.password.PasswordEncoder;

import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/** Each person picks the period their pending-case tabs open on. Made-up account. */
class UserServiceCasePeriodTest {

    private final UserRepository users = mock(UserRepository.class);
    private final UserService service = new UserService(users, mock(PasswordEncoder.class));
    private final UUID id = UUID.randomUUID();
    private final User user = User.builder().id(id).email("someone@example.com").fullName("Someone")
            .password("x").role(Role.RAASPAL_TEAM).build();

    @Test
    void aPersonSetsAndClearsTheirOwnDefault() {
        when(users.findById(id)).thenReturn(Optional.of(user));
        when(users.save(any(User.class))).thenAnswer(invocation -> invocation.getArgument(0));

        assertThat(service.setCasePeriodDefault(id, "weekly").casePeriodDefault()).isEqualTo("WEEKLY");
        assertThat(service.setCasePeriodDefault(id, "ALL").casePeriodDefault()).isEqualTo("ALL");
        assertThat(service.setCasePeriodDefault(id, " ").casePeriodDefault()).isNull();
        assertThatThrownBy(() -> service.setCasePeriodDefault(id, "YEARLY")).isInstanceOf(BadRequestException.class);
    }
}
