package com.sandeep.eventrabackend.config;

import com.sandeep.eventrabackend.model.UsernamePolicy;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.SmartInitializingSingleton;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.datasource.DataSourceUtils;
import org.springframework.jdbc.datasource.init.DatabasePopulator;
import org.springframework.jdbc.datasource.init.ResourceDatabasePopulator;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

@Component
public class UsernameMigrationInitializer implements SmartInitializingSingleton {

    static final long POSTGRESQL_MIGRATION_LOCK_ID = 8_247_719_306_703L;

    private final DataSource dataSource;
    private final TransactionTemplate transactionTemplate;
    private final DatabasePopulator migration;

    @Autowired
    public UsernameMigrationInitializer(
            DataSource dataSource,
            PlatformTransactionManager transactionManager) {
        this(
                dataSource,
                transactionManager,
                new ResourceDatabasePopulator(new ClassPathResource(
                        "db/migration/V3__username_normalized_uniqueness.sql")));
    }

    UsernameMigrationInitializer(
            DataSource dataSource,
            PlatformTransactionManager transactionManager,
            DatabasePopulator migration) {
        this.dataSource = dataSource;
        this.transactionTemplate = new TransactionTemplate(transactionManager);
        this.migration = migration;
    }

    @Override
    public void afterSingletonsInstantiated() {
        transactionTemplate.executeWithoutResult(status -> executeMigration());
    }

    private void executeMigration() {
        Connection connection = DataSourceUtils.getConnection(dataSource);
        try {
            String databaseProductName = connection.getMetaData().getDatabaseProductName();
            if ("PostgreSQL".equals(databaseProductName)) {
                try (Statement statement = connection.createStatement()) {
                    statement.execute("SELECT pg_advisory_xact_lock(" + POSTGRESQL_MIGRATION_LOCK_ID + ")");
                }
            }
            if ("H2".equals(databaseProductName)) {
                executeH2Migration(connection);
            } else {
                migration.populate(connection);
            }
        } catch (SQLException ex) {
            throw new IllegalStateException("Username normalization migration failed", ex);
        } finally {
            DataSourceUtils.releaseConnection(connection, dataSource);
        }
    }

    private void executeH2Migration(Connection connection) throws SQLException {
        try (Statement statement = connection.createStatement()) {
            statement.execute("SET EXCLUSIVE 1");
        }
        try {
            validateH2LegacyUsernames(connection);
            migration.populate(connection);
        } finally {
            try (Statement statement = connection.createStatement()) {
                statement.execute("SET EXCLUSIVE 0");
            }
        }
    }

    private void validateH2LegacyUsernames(Connection connection) throws SQLException {
        List<Long> invalidIds = new ArrayList<>();
        Map<String, List<Long>> idsByNormalizedUsername = new HashMap<>();
        try (Statement statement = connection.createStatement();
             ResultSet rows = statement.executeQuery("SELECT id, username FROM users ORDER BY id")) {
            while (rows.next()) {
                long id = rows.getLong("id");
                String normalizedUsername;
                try {
                    normalizedUsername = UsernamePolicy.normalizeKey(rows.getString("username"));
                } catch (IllegalArgumentException ex) {
                    invalidIds.add(id);
                    continue;
                }

                idsByNormalizedUsername
                        .computeIfAbsent(normalizedUsername, ignored -> new ArrayList<>())
                        .add(id);
            }
        }

        invalidIds.sort(Long::compareTo);
        List<List<Long>> collisionGroups = idsByNormalizedUsername.values().stream()
                .filter(ids -> ids.size() > 1)
                .map(ids -> ids.stream().sorted().toList())
                .sorted(Comparator.comparingLong(ids -> ids.get(0)))
                .toList();

        List<String> problems = new ArrayList<>(2);
        if (!invalidIds.isEmpty()) {
            problems.add("invalid legacy username ids " + invalidIds);
        }
        if (!collisionGroups.isEmpty()) {
            problems.add("normalized username collision id groups " + collisionGroups);
        }
        if (!problems.isEmpty()) {
            throw new IllegalStateException(
                    "H2 username migration preflight failed: " + String.join("; ", problems));
        }
    }
}
