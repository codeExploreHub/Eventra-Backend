package com.sandeep.eventrabackend.controller;

import com.sandeep.eventrabackend.model.Role;
import com.sandeep.eventrabackend.model.User;
import com.sandeep.eventrabackend.repository.HackathonRegistrationRepository;
import com.sandeep.eventrabackend.repository.NotificationRepository;
import com.sandeep.eventrabackend.repository.UserRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;

import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class UsernameAvailabilityTests {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private NotificationRepository notificationRepository;

    @Autowired
    private HackathonRegistrationRepository hackathonRegistrationRepository;

    @Autowired
    private PasswordEncoder passwordEncoder;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @BeforeEach
    void setUp() {
        notificationRepository.deleteAll();
        hackathonRegistrationRepository.deleteAll();
        userRepository.deleteAll();

        userRepository.save(User.builder()
                .firstName("John")
                .lastName("Doe")
                .email("john@example.com")
                .username("JohnDoe")
                .password(passwordEncoder.encode("password"))
                .role(Role.CLIENT)
                .build());

        userRepository.save(User.builder()
                .firstName("Jane")
                .lastName("Smith")
                .email("jane@example.com")
                .username("JaneSmith")
                .password(passwordEncoder.encode("password"))
                .role(Role.CLIENT)
                .build());
    }

    @Test
    @DisplayName("GET username availability trims and returns an available candidate")
    void usernameAvailability_validUnusedCandidate_returnsTrimmedAvailableResponse() throws Exception {
        mockMvc.perform(get("/api/users/username-availability")
                        .with(user("john@example.com"))
                        .queryParam("username", "  NewUser  "))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.username").value("NewUser"))
                .andExpect(jsonPath("$.available").value(true));
    }

    @Test
    @DisplayName("GET username availability compares another user's username case-insensitively")
    void usernameAvailability_otherUsersUsernameWithDifferentCase_returnsUnavailable() throws Exception {
        mockMvc.perform(get("/api/users/username-availability")
                        .with(user("john@example.com"))
                        .queryParam("username", "  JANESMITH  "))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.username").value("JANESMITH"))
                .andExpect(jsonPath("$.available").value(false));
    }

    @Test
    @DisplayName("GET username availability uses the migrated normalized key for a legacy username")
    void usernameAvailability_otherUsersLegacyWhitespaceUsername_returnsUnavailable() throws Exception {
        seedLegacyUsername("jane@example.com", " JaneSmith ", "janesmith");

        mockMvc.perform(get("/api/users/username-availability")
                        .with(user("john@example.com"))
                        .queryParam("username", "  JANESMITH  "))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.username").value("JANESMITH"))
                .andExpect(jsonPath("$.available").value(false));
    }

    @Test
    @DisplayName("GET username availability treats the authenticated user's username as available")
    void usernameAvailability_ownUsernameWithDifferentCase_returnsAvailable() throws Exception {
        mockMvc.perform(get("/api/users/username-availability")
                        .with(user("john@example.com"))
                        .queryParam("username", "  JOHNDOE  "))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.username").value("JOHNDOE"))
                .andExpect(jsonPath("$.available").value(true));
    }

    @Test
    @DisplayName("GET username availability excludes the current user's migrated normalized key")
    void usernameAvailability_ownLegacyWhitespaceUsername_returnsAvailable() throws Exception {
        seedLegacyUsername("john@example.com", " JohnDoe ", "johndoe");

        mockMvc.perform(get("/api/users/username-availability")
                        .with(user("john@example.com"))
                        .queryParam("username", "  JOHNDOE  "))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.username").value("JOHNDOE"))
                .andExpect(jsonPath("$.available").value(true));
    }

    @Test
    @DisplayName("GET username availability requires authentication")
    void usernameAvailability_withoutAuthentication_returnsUnauthorized() throws Exception {
        mockMvc.perform(get("/api/users/username-availability")
                        .queryParam("username", "NewUser"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    @DisplayName("GET username availability rejects a missing candidate")
    void usernameAvailability_missingCandidate_returnsBadRequest() throws Exception {
        mockMvc.perform(get("/api/users/username-availability")
                        .with(user("john@example.com")))
                .andExpect(status().isBadRequest());
    }

    @ParameterizedTest(name = "invalid candidate [{0}] returns 400")
    @ValueSource(strings = {"   ", "ab", "abcdefghijklmnopqrstuvwxyzabcdefghijklmnopqrstuvwxy"})
    void usernameAvailability_invalidCandidate_returnsBadRequest(String candidate) throws Exception {
        mockMvc.perform(get("/api/users/username-availability")
                        .with(user("john@example.com"))
                        .queryParam("username", candidate))
                .andExpect(status().isBadRequest());
    }

    private void seedLegacyUsername(String email, String username, String normalizedUsername) {
        jdbcTemplate.update(
                "update users set username = ?, username_normalized = ? where email = ?",
                username,
                normalizedUsername,
                email);
    }
}
