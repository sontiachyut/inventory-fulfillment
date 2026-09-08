package dev.sonti.inventory;

import java.time.Clock;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.context.annotation.Bean;
import org.springframework.core.env.Environment;

@SpringBootApplication
public class Application {
    public static void main(String[] args) {
        SpringApplication.run(Application.class, args);
    }

    @Bean
    Clock clock() {
        return Clock.systemUTC();
    }

    @Bean
    ApplicationRunner localDemoGuard(Environment environment) {
        return args -> {
            if (!environment.matchesProfiles("local-demo")) {
                throw new IllegalStateException("Only local-demo is implemented. Start with --spring.profiles.active=local-demo. Not for public deployment.");
            }
        };
    }
}
