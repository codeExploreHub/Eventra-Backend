package com.sandeep.eventrabackend.config;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.ClassPathResource;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.embedded.EmbeddedDatabaseBuilder;
import org.springframework.jdbc.datasource.embedded.EmbeddedDatabaseType;
import org.springframework.jdbc.datasource.init.ResourceDatabasePopulator;

import javax.sql.DataSource;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class UsernameNormalizationMigrationTests {

    @Test
    @DisplayName("V3 backfills normalized usernames in a populated users table")
    void migrate_populatedUsers_backfillsAndEnforcesCaseInsensitiveUniqueness() {
        DataSource dataSource = populatedUsersDatabase("Alice", "Bob");
        JdbcTemplate jdbcTemplate = new JdbcTemplate(dataSource);

        runMigration(dataSource);

        assertThat(jdbcTemplate.queryForList(
                "select username_normalized from users order by id", String.class))
                .containsExactly("alice", "bob");
        assertThatThrownBy(() -> jdbcTemplate.update(
                "insert into users (id, username, username_normalized) values (3, 'ALICE', 'alice')"))
                .isInstanceOf(DataAccessException.class);
    }

    @Test
    @DisplayName("V3 rejects pre-existing usernames that differ only by case")
    void migrate_existingCaseInsensitiveDuplicates_failsSafely() {
        DataSource dataSource = populatedUsersDatabase("Alice", "alice");

        assertThatThrownBy(() -> runMigration(dataSource))
                .isInstanceOf(DataAccessException.class);
    }

    private DataSource populatedUsersDatabase(String firstUsername, String secondUsername) {
        DataSource dataSource = new EmbeddedDatabaseBuilder()
                .generateUniqueName(true)
                .setType(EmbeddedDatabaseType.H2)
                .build();
        JdbcTemplate jdbcTemplate = new JdbcTemplate(dataSource);
        jdbcTemplate.execute("create table users (id bigint primary key, username varchar(50) not null unique)");
        jdbcTemplate.update("insert into users (id, username) values (1, ?)", firstUsername);
        jdbcTemplate.update("insert into users (id, username) values (2, ?)", secondUsername);
        return dataSource;
    }

    private void runMigration(DataSource dataSource) {
        new ResourceDatabasePopulator(new ClassPathResource(
                "db/migration/V3__username_normalized_uniqueness.sql"))
                .execute(dataSource);
    }
}
