package com.utewedding.app;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.autoconfigure.security.servlet.UserDetailsServiceAutoConfiguration;
import org.springframework.scheduling.annotation.EnableScheduling;

/** Entry point for the isolated Spring Boot migration application. */
@SpringBootApplication(exclude = UserDetailsServiceAutoConfiguration.class)
@EnableScheduling
public class UteWeddingApplication {

    public static void main(String[] args) {
        SpringApplication.run(UteWeddingApplication.class, args);
    }
}
