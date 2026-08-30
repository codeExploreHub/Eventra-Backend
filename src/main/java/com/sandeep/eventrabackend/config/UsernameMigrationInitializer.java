package com.sandeep.eventrabackend.config;

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
import java.sql.SQLException;
import java.sql.Statement;

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
            if ("PostgreSQL".equals(connection.getMetaData().getDatabaseProductName())) {
                try (Statement statement = connection.createStatement()) {
                    statement.execute("SELECT pg_advisory_xact_lock(" + POSTGRESQL_MIGRATION_LOCK_ID + ")");
                }
            }
            migration.populate(connection);
        } catch (SQLException ex) {
            throw new IllegalStateException("Username normalization migration failed", ex);
        } finally {
            DataSourceUtils.releaseConnection(connection, dataSource);
        }
    }
}
