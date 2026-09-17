package com.utewedding.app.config;

import javax.sql.DataSource;
import net.javacrumbs.shedlock.core.LockProvider;
import net.javacrumbs.shedlock.core.LockConfiguration;
import net.javacrumbs.shedlock.core.SimpleLock;
import net.javacrumbs.shedlock.provider.jdbctemplate.JdbcTemplateLockProvider;
import net.javacrumbs.shedlock.spring.annotation.EnableSchedulerLock;
import java.util.Optional;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.core.JdbcTemplate;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

@Configuration
@EnableSchedulerLock(defaultLockAtMostFor = "10m")
public class SchedulerLockConfig {
    private static final Logger LOGGER = LoggerFactory.getLogger(SchedulerLockConfig.class);

    @Bean
    LockProvider lockProvider(DataSource dataSource) {
        LockProvider delegate = new JdbcTemplateLockProvider(
                JdbcTemplateLockProvider.Configuration.builder()
                        .withJdbcTemplate(new JdbcTemplate(dataSource))
                        .usingDbTime()
                        .build());
        return configuration -> {
            try {
                Optional<SimpleLock> lock = delegate.lock(configuration);
                if (lock.isEmpty()) LOGGER.debug("ShedLock not acquired for job {}", configuration.getName());
                return lock.map(acquired -> () -> {
                    try {
                        acquired.unlock();
                    } catch (RuntimeException exception) {
                        LOGGER.error("ShedLock release failed for job {}", configuration.getName(), exception);
                    }
                });
            } catch (RuntimeException exception) {
                LOGGER.error("ShedLock acquisition failed for job {}; skipping this run",
                        configuration.getName(), exception);
                return Optional.empty();
            }
        };
    }
}
