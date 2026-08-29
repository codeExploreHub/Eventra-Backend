package com.sandeep.eventrabackend.controller;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sandeep.eventrabackend.dto.request.UserProfileUpdateRequest;
import com.sandeep.eventrabackend.model.Role;
import com.sandeep.eventrabackend.model.User;
import com.sandeep.eventrabackend.repository.UserRepository;
import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.MediaType;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.test.web.servlet.MockMvc;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.doThrow;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class UserProfileAtomicConflictTests {

    @Autowired
    private MockMvc mockMvc;

    @MockitoSpyBean
    private UserRepository userRepository;

    @Autowired
    private PasswordEncoder passwordEncoder;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private EntityManager entityManager;

    @BeforeEach
    void setUp() {
        userRepository.deleteAll();
        userRepository.save(User.builder()
                .firstName("Original")
                .lastName("Name")
                .email("owner@example.com")
                .username("OriginalUser")
                .password(passwordEncoder.encode("password"))
                .role(Role.CLIENT)
                .build());
    }

    @Test
    @DisplayName("PUT profile maps an atomic uniqueness race to 409 and rolls back all fields")
    void updateUserProfile_uniqueConstraintRace_returnsConflictAndRollsBackProfile() throws Exception {
        doReturn(false).when(userRepository)
                .existsByUsernameIgnoreCaseAndIdNot(eq("RacingUser"), anyLong());
        doThrow(new DataIntegrityViolationException("simulated concurrent username claim"))
                .when(userRepository).saveAndFlush(any(User.class));

        UserProfileUpdateRequest request = UserProfileUpdateRequest.builder()
                .firstName("Changed")
                .lastName("Profile")
                .username("RacingUser")
                .build();

        mockMvc.perform(put("/api/users/profile")
                        .with(user("owner@example.com"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.message").value("Username is already in use"));

        entityManager.clear();
        User persisted = userRepository.findByEmail("owner@example.com").orElseThrow();
        assertThat(persisted.getFirstName()).isEqualTo("Original");
        assertThat(persisted.getLastName()).isEqualTo("Name");
        assertThat(persisted.getUsername()).isEqualTo("OriginalUser");
    }
}
