package com.sandeep.eventrabackend.repository;

import com.sandeep.eventrabackend.config.UsernameMigrationInitializer;
import com.sandeep.eventrabackend.model.Role;
import com.sandeep.eventrabackend.model.User;
import com.sandeep.eventrabackend.support.LegacyUsernameMigrationFixture.MigratedUsername;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.ActiveProfiles;

import static com.sandeep.eventrabackend.support.LegacyUsernameMigrationFixture.migrate;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@DataJpaTest
@ActiveProfiles("test")
@Import(UsernameMigrationInitializer.class)
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

    @Test
    @DisplayName("Entity persistence uses the same control-whitespace key as V3")
    void saveAndFlush_controlWhitespaceUsername_matchesMigratedNormalizedKey() {
        MigratedUsername migrated = migrate("\t\u001f EntityUser \r\n");

        User saved = userRepository.saveAndFlush(
                user("entity@example.com", migrated.username()));

        assertThat(saved.getUsername()).isEqualTo("EntityUser");
        assertThat(saved.getUsernameNormalized())
                .isEqualTo(migrated.normalizedUsername())
                .isEqualTo("entityuser");
    }

    @ParameterizedTest(name = "entity persistence rejects invalid username [{0}]")
    @ValueSource(strings = {
            "İXX",
            "user-name",
            "user.name",
            "user name",
            "user!",
            "\u00a0user\u00a0"
    })
    @DisplayName("Entity persistence rejects usernames outside the ASCII contract")
    void saveAndFlush_invalidAsciiUsername_rejectsBeforePersistence(String username) {
        assertThatThrownBy(() ->
                userRepository.saveAndFlush(user("invalid@example.com", username)))
                .isInstanceOf(RuntimeException.class)
                .hasMessageContaining("Username must be 3 to 50 ASCII letters, digits, or underscores");
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
