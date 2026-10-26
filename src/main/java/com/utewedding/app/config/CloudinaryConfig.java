package com.utewedding.app.config;

import com.cloudinary.Cloudinary;
import java.util.LinkedHashMap;
import java.util.Map;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/** Cloudinary connection properties bound from CLOUDINARY_* environment variables. */
@Configuration
@ConfigurationProperties(prefix = "cloudinary")
public class CloudinaryConfig {

    private String cloudName = "";
    private String apiKey = "";
    private String apiSecret = "";

    @Bean
    public Cloudinary cloudinary() {
        Map<String, Object> options = new LinkedHashMap<>();
        options.put("cloud_name", cloudName);
        options.put("api_key", apiKey);
        options.put("api_secret", apiSecret);
        options.put("secure", true);
        return new Cloudinary(options);
    }

    public boolean isConfigured() {
        return hasText(cloudName) && hasText(apiKey) && hasText(apiSecret);
    }

    private static boolean hasText(String value) {
        return value != null && !value.isBlank();
    }

    public String getCloudName() {
        return cloudName;
    }

    public void setCloudName(String cloudName) {
        this.cloudName = cloudName;
    }

    public String getApiKey() {
        return apiKey;
    }

    public void setApiKey(String apiKey) {
        this.apiKey = apiKey;
    }

    public String getApiSecret() {
        return apiSecret;
    }

    public void setApiSecret(String apiSecret) {
        this.apiSecret = apiSecret;
    }
}
