package dev.sonti.inventory;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.json.JsonMapper;

@Configuration
@Profile("postgres-local")
class PostgresConfiguration {
    @Bean
    ReservationService postgresReservations(JdbcTemplate sql, PlatformTransactionManager manager, JsonMapper json) {
        return new PostgresReservations(sql, new TransactionTemplate(manager), json);
    }
}
