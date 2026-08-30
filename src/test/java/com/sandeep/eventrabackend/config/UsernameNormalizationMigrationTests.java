package com.sandeep.eventrabackend.config;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.CannotGetJdbcConnectionException;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.embedded.EmbeddedDatabaseBuilder;
import org.springframework.jdbc.datasource.embedded.EmbeddedDatabaseType;
import org.springframework.jdbc.datasource.init.ResourceDatabasePopulator;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;

import javax.sql.DataSource;
import java.sql.SQLException;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.catchThrowable;

class UsernameNormalizationMigrationTests {

    @Test
    @DisplayName("V3 applies Java trim semantics to legacy usernames before enforcing uniqueness")
    void migrate_legacyControlWhitespaceUsername_backfillsAndEnforcesNormalizedUniqueness() {
        DataSource dataSource = populatedUsersDatabase(
                "\u0000\t\u001f\u0020Alice\u0020\r\u0000",
                "Bob");
        JdbcTemplate jdbcTemplate = new JdbcTemplate(dataSource);

        runInitializerMigration(dataSource);

        assertThat(jdbcTemplate.queryForList(
                "select username from users order by id", String.class))
                .containsExactly("Alice", "Bob");
        assertThat(jdbcTemplate.queryForList(
                "select username_normalized from users order by id", String.class))
                .containsExactly("alice", "bob");
        assertThatThrownBy(() -> jdbcTemplate.update(
                "insert into users (id, username, username_normalized) values (3, 'ALICE', 'alice')"))
                .isInstanceOf(DataAccessException.class);
    }

    @ParameterizedTest(name = "V3 rejects invalid legacy username [{0}]")
    @ValueSource(strings = {
            "İXX",
            "user-name",
            "user.name",
            "user name",
            "user!",
            "\u00a0user\u00a0"
    })
    @DisplayName("V3 fails closed when a trimmed legacy username is outside the ASCII contract")
    void migrate_invalidLegacyUsername_failsWithNamedDiagnostic(String invalidUsername) {
        DataSource dataSource = populatedUsersDatabase(invalidUsername, "ValidUser");

        assertThatThrownBy(() -> runMigration(dataSource))
                .isInstanceOf(DataAccessException.class)
                .rootCause()
                .hasMessageContaining("CK_USERS_USERNAME_ASCII");
    }

    @Test
    @DisplayName("V3 constraints reject direct invalid or inconsistent username writes")
    void migrate_thenDirectInvalidWrites_areRejectedByNamedConstraints() {
        DataSource dataSource = populatedUsersDatabase("Alice", "Bob");
        JdbcTemplate jdbcTemplate = new JdbcTemplate(dataSource);
        runMigration(dataSource);

        assertThatThrownBy(() -> jdbcTemplate.update(
                "insert into users (id, username, username_normalized) values (3, 'bad-name', 'bad-name')"))
                .isInstanceOf(DataAccessException.class)
                .hasMessageContaining("CK_USERS_USERNAME_ASCII");
        assertThatThrownBy(() -> jdbcTemplate.update(
                "insert into users (id, username, username_normalized) values (4, 'Charlie', 'wrong')"))
                .isInstanceOf(DataAccessException.class)
                .hasMessageContaining("CK_USERS_USERNAME_NORMALIZED_CONSISTENT");
    }

    @Test
    @DisplayName("V3 can run twice without weakening username constraints")
    void migrate_secondStartup_remainsSafeAndIdempotent() {
        DataSource dataSource = populatedUsersDatabase("Alice", "Bob");
        JdbcTemplate jdbcTemplate = new JdbcTemplate(dataSource);

        runMigration(dataSource);
        runMigration(dataSource);

        assertThat(jdbcTemplate.queryForList(
                "select username from users order by id", String.class))
                .containsExactly("Alice", "Bob");
        assertThat(jdbcTemplate.queryForList(
                "select username_normalized from users order by id", String.class))
                .containsExactly("alice", "bob");
    }

    @Test
    @DisplayName("V3 rejects pre-existing usernames that differ only by case")
    void migrate_existingCaseInsensitiveDuplicates_failsSafely() {
        DataSource dataSource = populatedUsersDatabase("Alice", "alice");

        assertThatThrownBy(() -> runMigration(dataSource))
                .isInstanceOf(DataAccessException.class);
    }

    @Test
    @DisplayName("H2 startup preserves invalid legacy rows when migration fails")
    void initialize_invalidLegacyUsername_failsWithoutRewritingRows() {
        DataSource dataSource = populatedUsersDatabaseWithNormalizedColumn(
                "\tbad-name\t",
                "ValidUser");
        JdbcTemplate jdbcTemplate = new JdbcTemplate(dataSource);
        var rowsBeforeMigration = legacyUsernameState(jdbcTemplate);

        Throwable failure = catchThrowable(() -> runInitializerMigration(dataSource));

        assertThat(legacyUsernameState(jdbcTemplate)).isEqualTo(rowsBeforeMigration);
        assertThat(failure)
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("invalid legacy username")
                .hasMessageContaining("id 1");
    }

    @Test
    @DisplayName("H2 startup preserves colliding legacy rows when migration fails")
    void initialize_normalizedCollision_failsWithoutRewritingRows() {
        DataSource dataSource = populatedUsersDatabaseWithNormalizedColumn(
                " Alice ",
                "alice");
        JdbcTemplate jdbcTemplate = new JdbcTemplate(dataSource);
        var rowsBeforeMigration = legacyUsernameState(jdbcTemplate);

        Throwable failure = catchThrowable(() -> runInitializerMigration(dataSource));

        assertThat(legacyUsernameState(jdbcTemplate)).isEqualTo(rowsBeforeMigration);
        assertThat(failure)
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("normalized username collision")
                .hasMessageContaining("ids 1 and 2");
    }

    @Test
    @DisplayName("H2 startup rejects concurrent writes for the complete migration window")
    void initialize_h2Migration_rejectsConcurrentWritesUntilMigrationCompletes() throws Exception {
        DataSource dataSource = populatedUsersDatabaseWithNormalizedColumn("Alice", "Bob");
        JdbcTemplate jdbcTemplate = new JdbcTemplate(dataSource);
        CountDownLatch migrationEntered = new CountDownLatch(1);
        CountDownLatch releaseMigration = new CountDownLatch(1);
        var executor = Executors.newFixedThreadPool(2);

        try {
            var initializerFuture = executor.submit(() -> new UsernameMigrationInitializer(
                    dataSource,
                    new DataSourceTransactionManager(dataSource),
                    connection -> {
                        migrationEntered.countDown();
                        try {
                            if (!releaseMigration.await(5, TimeUnit.SECONDS)) {
                                throw new SQLException("Timed out waiting to release migration");
                            }
                        } catch (InterruptedException ex) {
                            Thread.currentThread().interrupt();
                            throw new SQLException("Interrupted while waiting to release migration", ex);
                        }
                    }).afterSingletonsInstantiated());

            assertThat(migrationEntered.await(5, TimeUnit.SECONDS)).isTrue();
            var writerFuture = executor.submit(() -> jdbcTemplate.update(
                    "update users set username = 'Writer' where id = 2"));

            assertThatThrownBy(() -> writerFuture.get(5, TimeUnit.SECONDS))
                    .isInstanceOf(ExecutionException.class)
                    .hasCauseInstanceOf(CannotGetJdbcConnectionException.class);

            releaseMigration.countDown();
            initializerFuture.get(5, TimeUnit.SECONDS);
            assertThat(jdbcTemplate.update(
                    "update users set username = 'Writer' where id = 2"))
                    .isEqualTo(1);
        } finally {
            releaseMigration.countDown();
            executor.shutdownNow();
        }
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

    private DataSource populatedUsersDatabaseWithNormalizedColumn(
            String firstUsername,
            String secondUsername) {
        DataSource dataSource = new EmbeddedDatabaseBuilder()
                .generateUniqueName(true)
                .setType(EmbeddedDatabaseType.H2)
                .build();
        JdbcTemplate jdbcTemplate = new JdbcTemplate(dataSource);
        jdbcTemplate.execute("""
                create table users (
                    id bigint primary key,
                    username varchar(50) not null unique,
                    username_normalized varchar(50)
                )
                """);
        jdbcTemplate.update("insert into users (id, username) values (1, ?)", firstUsername);
        jdbcTemplate.update("insert into users (id, username) values (2, ?)", secondUsername);
        return dataSource;
    }

    private java.util.List<LegacyUsernameState> legacyUsernameState(JdbcTemplate jdbcTemplate) {
        return jdbcTemplate.query(
                "select username, username_normalized from users order by id",
                (resultSet, rowNumber) -> new LegacyUsernameState(
                        resultSet.getString("username"),
                        resultSet.getString("username_normalized")));
    }

    private void runInitializerMigration(DataSource dataSource) {
        new UsernameMigrationInitializer(
                dataSource,
                new DataSourceTransactionManager(dataSource),
                new ResourceDatabasePopulator(new ClassPathResource(
                        "db/migration/V3__username_normalized_uniqueness.sql")))
                .afterSingletonsInstantiated();
    }

    private void runMigration(DataSource dataSource) {
        new ResourceDatabasePopulator(new ClassPathResource(
                "db/migration/V3__username_normalized_uniqueness.sql"))
                .execute(dataSource);
    }

    private record LegacyUsernameState(String username, String normalizedUsername) {
    }
}
