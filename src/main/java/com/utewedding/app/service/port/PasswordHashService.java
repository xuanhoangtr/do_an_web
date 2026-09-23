package com.utewedding.app.service.port;

public interface PasswordHashService {
    String hash(String rawPassword);
    boolean matches(String rawPassword, String encodedPassword);
}
