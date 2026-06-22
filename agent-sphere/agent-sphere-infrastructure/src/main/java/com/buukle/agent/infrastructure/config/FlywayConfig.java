package com.buukle.agent.infrastructure.config;

import org.springframework.boot.autoconfigure.flyway.FlywayConfigurationCustomizer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class FlywayConfig {

    @Bean
    public FlywayConfigurationCustomizer flywayCustomizer() {
        return configuration -> configuration
                .baselineOnMigrate(true)
                .baselineVersion("0");
    }
}
