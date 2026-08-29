package com.sandeep.eventrabackend.repository;

import com.sandeep.eventrabackend.model.Role;
import com.sandeep.eventrabackend.model.User;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.test.context.ActiveProfiles;

import static org.assertj.core.api.Assertions.assertThatThrownBy;

@DataJpaTest
@ActiveProfiles("test")
class UserUsernameUniquenessIntegrationTests {

    @Autowired
    private UserRepository userRepository;

    @Test
    @DisplayName("The database rejects usernames that differ only by case")
    void saveAndFlush_usernameDiffersOnlyByCase_rejectsDuplicateAtomically() {
        userRepository.saveAndFlush(user("first@example.com", "CaseSensitive"));

        assertThatThrownBy(() ->
                userRepository.saveAndFlush(user("second@example.com", "casesensitive")))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    private User user(String email, String username) {
        return User.builder()
                .firstName("Test")
                .lastName("User")
                .email(email)
                .username(username)
                .password("encoded-password")
                .role(Role.CLIENT)
                .build();
    }
}
