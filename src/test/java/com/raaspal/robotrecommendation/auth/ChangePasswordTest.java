package com.raaspal.robotrecommendation.auth;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.raaspal.robotrecommendation.common.enums.Role;
import com.raaspal.robotrecommendation.user.entity.User;
import com.raaspal.robotrecommendation.user.repository.UserRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.test.context.support.WithAnonymousUser;
import org.springframework.security.test.context.support.TestExecutionEvent;
import org.springframework.security.test.context.support.WithUserDetails;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.annotation.Transactional;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Changing your own password.
 *
 * <p>The account acted on comes from the authenticated principal, so the tests below
 * pin the two properties that matter: the current password is genuinely required, and
 * the endpoint cannot be aimed at anyone else.
 */
@SpringBootTest
@AutoConfigureMockMvc
@Transactional
class ChangePasswordTest {

    private static final String URL = "/api/v1/auth/change-password";
    private static final String EMAIL = "pw-test@raaspal.com";
    private static final String OTHER = "pw-other@raaspal.com";
    private static final String CURRENT = "CurrentPass1";

    @Autowired private MockMvc mockMvc;
    @Autowired private ObjectMapper objectMapper;
    @Autowired private UserRepository userRepository;
    @Autowired private PasswordEncoder passwordEncoder;

    @BeforeEach
    void setUp() {
        for (String email : new String[] {EMAIL, OTHER}) {
            userRepository.findByEmailIgnoreCase(email).ifPresent(userRepository::delete);
            userRepository.save(User.builder()
                    .email(email)
                    .password(passwordEncoder.encode(CURRENT))
                    .fullName("Password Test")
                    .role(Role.RAASPAL_TEAM)
                    .isActive(true)
                    .build());
        }
    }

    private String body(String current, String next) throws Exception {
        return objectMapper.writeValueAsString(
                Map.of("currentPassword", current, "newPassword", next));
    }

    private String storedHash(String email) {
        return userRepository.findByEmailIgnoreCase(email).orElseThrow().getPassword();
    }

    /** The happy path: the stored hash changes and the new password verifies. */
    @Test
    @WithUserDetails(value = EMAIL, setupBefore = TestExecutionEvent.TEST_EXECUTION)
    void thePasswordChangesWhenTheCurrentOneIsCorrect() throws Exception {
        mockMvc.perform(post(URL).contentType(MediaType.APPLICATION_JSON)
                        .content(body(CURRENT, "BrandNewPass9")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.message").value("Password changed"));

        assertThat(passwordEncoder.matches("BrandNewPass9", storedHash(EMAIL))).isTrue();
        assertThat(passwordEncoder.matches(CURRENT, storedHash(EMAIL))).isFalse();
    }

    /**
     * The point of asking for the current password: a valid token is not on its own
     * proof that the account owner is the one at the keyboard.
     */
    @Test
    @WithUserDetails(value = EMAIL, setupBefore = TestExecutionEvent.TEST_EXECUTION)
    void theWrongCurrentPasswordIsRejectedAndNothingChanges() throws Exception {
        String before = storedHash(EMAIL);

        mockMvc.perform(post(URL).contentType(MediaType.APPLICATION_JSON)
                        .content(body("NotMyPassword", "BrandNewPass9")))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value("Your current password is incorrect"));

        assertThat(storedHash(EMAIL)).isEqualTo(before);
    }

    /**
     * The account is taken from the principal, so a signed-in user changing their own
     * password cannot touch anybody else's — there is no parameter that would let them.
     */
    @Test
    @WithUserDetails(value = EMAIL, setupBefore = TestExecutionEvent.TEST_EXECUTION)
    void changingYourPasswordLeavesEveryOtherAccountAlone() throws Exception {
        String otherBefore = storedHash(OTHER);

        mockMvc.perform(post(URL).contentType(MediaType.APPLICATION_JSON)
                        .content(body(CURRENT, "BrandNewPass9")))
                .andExpect(status().isOk());

        assertThat(storedHash(OTHER)).isEqualTo(otherBefore);
        assertThat(passwordEncoder.matches(CURRENT, storedHash(OTHER))).isTrue();
    }

    /** Same floor as account creation; a shorter one here would be a back door. */
    @Test
    @WithUserDetails(value = EMAIL, setupBefore = TestExecutionEvent.TEST_EXECUTION)
    void aNewPasswordUnderEightCharactersIsRejected() throws Exception {
        String before = storedHash(EMAIL);

        mockMvc.perform(post(URL).contentType(MediaType.APPLICATION_JSON)
                        .content(body(CURRENT, "short1")))
                .andExpect(status().isBadRequest());

        assertThat(storedHash(EMAIL)).isEqualTo(before);
    }

    /** Re-saving the same password is almost always a mistake, and never useful. */
    @Test
    @WithUserDetails(value = EMAIL, setupBefore = TestExecutionEvent.TEST_EXECUTION)
    void reusingTheCurrentPasswordIsRejected() throws Exception {
        mockMvc.perform(post(URL).contentType(MediaType.APPLICATION_JSON)
                        .content(body(CURRENT, CURRENT)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message")
                        .value("The new password must be different from the current one"));
    }

    /** No token, no password change. */
    @Test
    @WithAnonymousUser
    void anonymousCallersAreRejected() throws Exception {
        mockMvc.perform(post(URL).contentType(MediaType.APPLICATION_JSON)
                        .content(body(CURRENT, "BrandNewPass9")))
                .andExpect(status().isUnauthorized());

        assertThat(passwordEncoder.matches(CURRENT, storedHash(EMAIL))).isTrue();
    }
}
