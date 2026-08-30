package com.sandeep.eventrabackend.controller;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sandeep.eventrabackend.dto.request.SignupRequest;
import com.sandeep.eventrabackend.model.Role;
import com.sandeep.eventrabackend.model.User;
import com.sandeep.eventrabackend.repository.EventRegistrationRepository;
import com.sandeep.eventrabackend.repository.EventRepository;
import com.sandeep.eventrabackend.repository.FeedbackAnalyticsRepository;
import com.sandeep.eventrabackend.repository.HackathonRegistrationRepository;
import com.sandeep.eventrabackend.repository.NotificationRepository;
import com.sandeep.eventrabackend.repository.UserRepository;
import com.sandeep.eventrabackend.support.LegacyUsernameMigrationFixture.MigratedUsername;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;

import static com.sandeep.eventrabackend.support.LegacyUsernameMigrationFixture.migrate;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest(properties = "app.rate-limit.enabled=false")
@AutoConfigureMockMvc
@ActiveProfiles("test")
class AuthUsernameGenerationTests {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private NotificationRepository notificationRepository;

    @Autowired
    private FeedbackAnalyticsRepository feedbackRepository;

    @Autowired
    private HackathonRegistrationRepository hackathonRegistrationRepository;

    @Autowired
    private EventRegistrationRepository eventRegistrationRepository;

    @Autowired
    private EventRepository eventRepository;

    @Autowired
    private PasswordEncoder passwordEncoder;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @BeforeEach
    void setUp() {
        notificationRepository.deleteAll();
        feedbackRepository.deleteAll();
        hackathonRegistrationRepository.deleteAll();
        eventRegistrationRepository.deleteAll();
        eventRepository.deleteAll();
        userRepository.deleteAll();
        userRepository.save(User.builder()
                .firstName("Existing")
                .lastName("User")
                .email("existing@example.com")
                .username("MixedCase")
                .password(passwordEncoder.encode("password123"))
                .role(Role.CLIENT)
                .build());
    }

    @Test
    @DisplayName("Signup generates the next username when the base differs only by case")
    void signup_baseUsernameDiffersOnlyByCase_generatesNextAvailableUsername() throws Exception {
        SignupRequest request = new SignupRequest();
        request.setFirstName("New");
        request.setLastName("User");
        request.setEmail("mixedcase@example.net");
        request.setPassword("password123");
        request.setConfirmPassword("password123");

        mockMvc.perform(post("/api/auth/signup")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.username").value("mixedcase1"));
    }

    @Test
    @DisplayName("Signup uses V3's key for a control-whitespace legacy username")
    void signup_baseUsernameMatchesMigratedControlWhitespaceUsername_generatesNextAvailableUsername() throws Exception {
        MigratedUsername migrated = migrate("\t\u001f MixedCase \r\n");
        jdbcTemplate.update(
                "update users set username = ?, username_normalized = ? where email = ?",
                migrated.username(),
                migrated.normalizedUsername(),
                "existing@example.com");
        SignupRequest request = new SignupRequest();
        request.setFirstName("New");
        request.setLastName("User");
        request.setEmail("mixedcase@example.net");
        request.setPassword("password123");
        request.setConfirmPassword("password123");

        mockMvc.perform(post("/api/auth/signup")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.username").value("mixedcase1"));
    }

    @Test
    @DisplayName("Signup replaces disallowed email-local characters and keeps collision suffixes ASCII")
    void signup_disallowedEmailLocalCharacters_generatesCollisionSafeAsciiUsername() throws Exception {
        userRepository.save(User.builder()
                .firstName("Existing")
                .lastName("Sanitized")
                .email("sanitized@example.com")
                .username("new_user_name")
                .password(passwordEncoder.encode("password123"))
                .role(Role.CLIENT)
                .build());

        SignupRequest request = signupRequest("New.User-Name@example.net");

        mockMvc.perform(post("/api/auth/signup")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.username").value("new_user_name1"));
    }

    @Test
    @DisplayName("Signup converts a Unicode email-local source to an ASCII username")
    void signup_unicodeEmailLocalSource_generatesAsciiUsername() throws Exception {
        SignupRequest request = signupRequest("İXX@example.net");

        mockMvc.perform(post("/api/auth/signup")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.username").value("_xx"));
    }

    @Test
    @DisplayName("Signup keeps a suffixed generated username within 50 characters")
    void signup_maximumLengthGeneratedUsernameCollision_truncatesBeforeSuffix() throws Exception {
        String fiftyCharacters = "a".repeat(50);
        userRepository.save(User.builder()
                .firstName("Existing")
                .lastName("Long")
                .email("long@example.com")
                .username(fiftyCharacters)
                .password(passwordEncoder.encode("password123"))
                .role(Role.CLIENT)
                .build());

        SignupRequest request = signupRequest("a".repeat(60) + "@example.net");

        mockMvc.perform(post("/api/auth/signup")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.username").value("a".repeat(49) + "1"));
    }

    private SignupRequest signupRequest(String email) {
        SignupRequest request = new SignupRequest();
        request.setFirstName("New");
        request.setLastName("User");
        request.setEmail(email);
        request.setPassword("password123");
        request.setConfirmPassword("password123");
        return request;
    }
}
