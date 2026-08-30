package com.sandeep.eventrabackend.config;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InOrder;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.jdbc.datasource.init.DatabasePopulator;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.SimpleTransactionStatus;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.DatabaseMetaData;
import java.sql.ResultSet;
import java.sql.Statement;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class UsernameMigrationInitializerTests {

    @Mock
    private DataSource dataSource;
    @Mock
    private PlatformTransactionManager transactionManager;
    @Mock
    private DatabasePopulator migration;
    @Mock
    private Connection connection;
    @Mock
    private DatabaseMetaData metadata;
    @Mock
    private Statement statement;
    @Mock
    private ResultSet resultSet;

    @Test
    void run_postgresql_acquiresTransactionLockBeforeMigration() throws Exception {
        when(transactionManager.getTransaction(any())).thenReturn(new SimpleTransactionStatus());
        when(dataSource.getConnection()).thenReturn(connection);
        when(connection.getMetaData()).thenReturn(metadata);
        when(metadata.getDatabaseProductName()).thenReturn("PostgreSQL");
        when(connection.createStatement()).thenReturn(statement);

        UsernameMigrationInitializer initializer =
                new UsernameMigrationInitializer(dataSource, transactionManager, migration);
        initializer.afterSingletonsInstantiated();

        InOrder order = inOrder(statement, migration, transactionManager);
        order.verify(statement).execute("SELECT pg_advisory_xact_lock(8247719306703)");
        order.verify(migration).populate(connection);
        order.verify(transactionManager).commit(any());
    }

    @Test
    void run_h2_preflightsLegacyRowsBeforeMigration() throws Exception {
        when(transactionManager.getTransaction(any())).thenReturn(new SimpleTransactionStatus());
        when(dataSource.getConnection()).thenReturn(connection);
        when(connection.getMetaData()).thenReturn(metadata);
        when(metadata.getDatabaseProductName()).thenReturn("H2");
        when(connection.createStatement()).thenReturn(statement);
        when(statement.executeQuery("SELECT id, username FROM users ORDER BY id"))
                .thenReturn(resultSet);
        when(resultSet.next()).thenReturn(false);

        UsernameMigrationInitializer initializer =
                new UsernameMigrationInitializer(dataSource, transactionManager, migration);
        initializer.afterSingletonsInstantiated();

        InOrder order = inOrder(statement, migration);
        order.verify(statement).execute("SET EXCLUSIVE 1");
        order.verify(statement).executeQuery("SELECT id, username FROM users ORDER BY id");
        order.verify(migration).populate(connection);
        order.verify(statement).execute("SET EXCLUSIVE 0");
    }
}
