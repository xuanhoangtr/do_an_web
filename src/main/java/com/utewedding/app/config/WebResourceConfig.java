package com.utewedding.app.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.resource.ResourceUrlEncodingFilter;

/** Enables Thymeleaf's resource URL transformer for content-hashed assets. */
@Configuration
public class WebResourceConfig {
    @Bean
    ResourceUrlEncodingFilter resourceUrlEncodingFilter() {
        return new ResourceUrlEncodingFilter();
    }
}
