package com.sandeep.eventrabackend.support;

import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.embedded.EmbeddedDatabase;
import org.springframework.jdbc.datasource.embedded.EmbeddedDatabaseBuilder;
import org.springframework.jdbc.datasource.embedded.EmbeddedDatabaseType;
import org.springframework.jdbc.datasource.init.ResourceDatabasePopulator;

public final class LegacyUsernameMigrationFixture {

    private LegacyUsernameMigrationFixture() {
    }

    public static MigratedUsername migrate(String legacyUsername) {
        EmbeddedDatabase database = new EmbeddedDatabaseBuilder()
                .generateUniqueName(true)
                .setType(EmbeddedDatabaseType.H2)
                .build();
        try {
            JdbcTemplate jdbcTemplate = new JdbcTemplate(database);
            jdbcTemplate.execute(
                    "create table users (id bigint primary key, username varchar(50) not null unique)");
            jdbcTemplate.update(
                    "insert into users (id, username) values (1, ?)", legacyUsername);

            new ResourceDatabasePopulator(new ClassPathResource(
                    "db/migration/V3__username_normalized_uniqueness.sql"))
                    .execute(database);

            return jdbcTemplate.queryForObject(
                    "select username, username_normalized from users where id = 1",
                    (resultSet, rowNumber) -> new MigratedUsername(
                            resultSet.getString("username"),
                            resultSet.getString("username_normalized")));
        } finally {
            database.shutdown();
        }
    }

    public record MigratedUsername(String username, String normalizedUsername) {
    }
}
