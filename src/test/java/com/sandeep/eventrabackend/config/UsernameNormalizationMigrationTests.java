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
import java.util.Arrays;
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

    @ParameterizedTest(name = "V3 rejects direct username shorter than three characters [{0}]")
    @ValueSource(strings = {"", "a", "aa"})
    @DisplayName("V3 explicitly constrains direct username writes to at least three characters")
    void migrate_thenDirectUnderlengthUsernameWrite_isRejectedByLengthConstraint(String username) {
        DataSource dataSource = populatedUsersDatabase("Alice", "Bob");
        JdbcTemplate jdbcTemplate = new JdbcTemplate(dataSource);
        runMigration(dataSource);
        jdbcTemplate.execute("alter table users drop constraint ck_users_username_ascii");

        assertThatThrownBy(() -> jdbcTemplate.update(
                "insert into users (id, username, username_normalized) values (3, ?, ?)",
                username,
                username))
                .isInstanceOf(DataAccessException.class)
                .hasMessageContaining("CK_USERS_USERNAME_LENGTH");
    }

    @ParameterizedTest(name = "V3 rejects direct normalized username shorter than three characters [{0}]")
    @ValueSource(strings = {"", "a", "aa"})
    @DisplayName("V3 explicitly constrains direct normalized username writes to at least three characters")
    void migrate_thenDirectUnderlengthNormalizedUsernameWrite_isRejectedByLengthConstraint(
            String normalizedUsername) {
        DataSource dataSource = populatedUsersDatabase("Alice", "Bob");
        JdbcTemplate jdbcTemplate = new JdbcTemplate(dataSource);
        runMigration(dataSource);
        jdbcTemplate.execute("alter table users drop constraint ck_users_username_normalized_consistent");

        assertThatThrownBy(() -> jdbcTemplate.update(
                "insert into users (id, username, username_normalized) values (3, 'Abc', ?)",
                normalizedUsername))
                .isInstanceOf(DataAccessException.class)
                .hasMessageContaining("CK_USERS_USERNAME_NORMALIZED_LENGTH");
    }

    @Test
    @DisplayName("V3 accepts direct username writes at the three and fifty character boundaries")
    void migrate_thenDirectBoundaryUsernameWrites_areAccepted() {
        DataSource dataSource = populatedUsersDatabase("Alice", "Bob");
        JdbcTemplate jdbcTemplate = new JdbcTemplate(dataSource);
        runMigration(dataSource);

        assertThat(jdbcTemplate.update(
                "insert into users (id, username, username_normalized) values (3, 'Abc', 'abc')"))
                .isEqualTo(1);
        assertThat(jdbcTemplate.update(
                "insert into users (id, username, username_normalized) values (4, ?, ?)",
                "A".repeat(50),
                "a".repeat(50)))
                .isEqualTo(1);
        assertThat(jdbcTemplate.queryForList(
                "select character_length(username) from users where id in (3, 4) order by id",
                Integer.class))
                .containsExactly(3, 50);
        assertThat(jdbcTemplate.queryForList(
                "select character_length(username_normalized) from users where id in (3, 4) order by id",
                Integer.class))
                .containsExactly(3, 50);
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
                .hasMessageContaining("ids [1]");
    }

    @Test
    @DisplayName("H2 startup preserves whitespace-only legacy rows when migration fails")
    void initialize_whitespaceOnlyLegacyUsername_failsWithoutRewritingRows() {
        DataSource dataSource = populatedUsersDatabaseWithNormalizedColumn(
                "\u0000\t\u001f\u0020",
                "ValidUser");
        JdbcTemplate jdbcTemplate = new JdbcTemplate(dataSource);
        var rowsBeforeMigration = legacyUsernameState(jdbcTemplate);

        Throwable failure = catchThrowable(() -> runInitializerMigration(dataSource));

        assertThat(legacyUsernameState(jdbcTemplate)).isEqualTo(rowsBeforeMigration);
        assertThat(failure)
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("invalid legacy username")
                .hasMessageContaining("ids [1]");
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
                .hasMessageContaining("id groups [[1, 2]]");
    }

    @Test
    @DisplayName("H2 startup reports every invalid legacy username by ID without rewriting rows")
    void initialize_multipleInvalidLegacyUsernames_reportsAllIdsWithoutRewritingRows() {
        DataSource dataSource = populatedUsersDatabaseWithNormalizedRows(
                new LegacyUserRow(8, "ValidUser", "stale-eight"),
                new LegacyUserRow(5, "bad.name", "stale-five"),
                new LegacyUserRow(2, "bad-name", "stale-two"));
        JdbcTemplate jdbcTemplate = new JdbcTemplate(dataSource);
        var rowsBeforeMigration = legacyUsernameState(jdbcTemplate);

        Throwable failure = catchThrowable(() -> runInitializerMigration(dataSource));

        assertThat(legacyUsernameState(jdbcTemplate)).isEqualTo(rowsBeforeMigration);
        assertThat(failure)
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("H2 username migration preflight failed: invalid legacy username ids [2, 5]");
        assertThat(diagnosticMessages(failure))
                .doesNotContain("bad.name", "bad-name", "stale-eight", "stale-five", "stale-two");
    }

    @Test
    @DisplayName("H2 startup reports every normalized collision group in deterministic ID order")
    void initialize_multipleNormalizedCollisionGroups_reportsAllIdsWithoutRewritingRows() {
        DataSource dataSource = populatedUsersDatabaseWithNormalizedRows(
                new LegacyUserRow(7, "ALICE", "stale-seven"),
                new LegacyUserRow(5, " BOB ", "stale-five"),
                new LegacyUserRow(4, "Alice", "stale-four"),
                new LegacyUserRow(2, "bob", "stale-two"),
                new LegacyUserRow(1, " alice ", "stale-one"));
        JdbcTemplate jdbcTemplate = new JdbcTemplate(dataSource);
        var rowsBeforeMigration = legacyUsernameState(jdbcTemplate);

        Throwable failure = catchThrowable(() -> runInitializerMigration(dataSource));

        assertThat(legacyUsernameState(jdbcTemplate)).isEqualTo(rowsBeforeMigration);
        assertThat(failure)
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("H2 username migration preflight failed: "
                        + "normalized username collision id groups [[1, 4, 7], [2, 5]]");
        assertThat(diagnosticMessages(failure))
                .doesNotContain(
                        "ALICE", "Alice", "alice", "BOB", "bob",
                        "stale-seven", "stale-five", "stale-four", "stale-two", "stale-one");
    }

    @Test
    @DisplayName("H2 startup reports mixed invalid and collision problems before rewriting any row")
    void initialize_mixedPreflightProblems_reportsAllIdsDeterministicallyAndRedactsValues() {
        DataSource dataSource = populatedUsersDatabaseWithNormalizedRows(
                new LegacyUserRow(9, " BOB ", "stale-nine"),
                new LegacyUserRow(8, "carol", "stale-eight"),
                new LegacyUserRow(7, " alice ", "stale-seven"),
                new LegacyUserRow(6, "BOB", "stale-six"),
                new LegacyUserRow(5, "bad.name", "stale-five"),
                new LegacyUserRow(4, "bad-name", "stale-four"),
                new LegacyUserRow(3, "Carol", "stale-three"),
                new LegacyUserRow(2, "Alice", "stale-two"));
        JdbcTemplate jdbcTemplate = new JdbcTemplate(dataSource);
        var rowsBeforeMigration = legacyUsernameState(jdbcTemplate);

        Throwable failure = catchThrowable(() -> runInitializerMigration(dataSource));

        assertThat(legacyUsernameState(jdbcTemplate)).isEqualTo(rowsBeforeMigration);
        assertThat(failure)
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("H2 username migration preflight failed: invalid legacy username ids [4, 5]; "
                        + "normalized username collision id groups [[2, 7], [3, 8], [6, 9]]");
        assertThat(diagnosticMessages(failure))
                .doesNotContain(
                        "bad.name", "bad-name", "Alice", "alice", "Carol", "carol", "BOB",
                        "stale-nine", "stale-eight", "stale-seven", "stale-six", "stale-five",
                        "stale-four", "stale-three", "stale-two");
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
        return populatedUsersDatabaseWithNormalizedRows(
                new LegacyUserRow(1, firstUsername, null),
                new LegacyUserRow(2, secondUsername, null));
    }

    private DataSource populatedUsersDatabaseWithNormalizedRows(LegacyUserRow... rows) {
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
        Arrays.stream(rows).forEach(row -> jdbcTemplate.update(
                "insert into users (id, username, username_normalized) values (?, ?, ?)",
                row.id(),
                row.username(),
                row.normalizedUsername()));
        return dataSource;
    }

    private java.util.List<LegacyUsernameState> legacyUsernameState(JdbcTemplate jdbcTemplate) {
        return jdbcTemplate.query(
                "select id, username, username_normalized from users order by id",
                (resultSet, rowNumber) -> new LegacyUsernameState(
                        resultSet.getLong("id"),
                        resultSet.getString("username"),
                        resultSet.getString("username_normalized")));
    }

    private String diagnosticMessages(Throwable failure) {
        StringBuilder messages = new StringBuilder();
        for (Throwable current = failure; current != null; current = current.getCause()) {
            messages.append(current.getMessage()).append('\n');
        }
        return messages.toString();
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

    private record LegacyUserRow(long id, String username, String normalizedUsername) {
    }

    private record LegacyUsernameState(long id, String username, String normalizedUsername) {
    }
}
