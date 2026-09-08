package dev.sonti.inventory;

import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.support.JdbcTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.postgresql.PostgreSQLContainer;
import org.testcontainers.utility.DockerImageName;

abstract class PostgresFixture {
    static final PostgreSQLContainer postgres = new PostgreSQLContainer(DockerImageName.parse("postgres@sha256:18cfe3ef5e6815560c98237d6216d1e5119702fb0f3894c8785dd58b8bbe5d73").asCompatibleSubstituteFor("postgres"))
            .withDatabaseName("reference_test");
    static HikariDataSource pool;
    static JdbcTemplate sql;
    static TransactionTemplate transactions;

    @BeforeAll static void startDatabase() {
        postgres.start(); // A missing Docker runtime fails the suite; never silently skip.
        pool = newPool();
        sql = new JdbcTemplate(pool);
        transactions = new TransactionTemplate(new JdbcTransactionManager(pool));
        Flyway.configure().dataSource(pool).load().migrate();
    }
    static HikariDataSource newPool() {
        var config = new HikariConfig();
        config.setJdbcUrl(postgres.getJdbcUrl());
        config.setUsername(postgres.getUsername());
        config.setPassword(postgres.getPassword());
        config.setMaximumPoolSize(16);
        config.setConnectionTimeout(5000);
        config.setConnectionInitSql("SET statement_timeout = '10s'");
        return new HikariDataSource(config);
    }
    @AfterAll static void stopDatabase() {
        if (pool != null) pool.close();
        postgres.stop();
    }
}
