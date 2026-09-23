package com.utewedding.app.service.impl;

import com.utewedding.app.service.port.PasswordHashService;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.context.annotation.Primary;

/** Application password service: writes Argon2id and reads legacy BCrypt. */
@Service
@Primary
public class MigratingPasswordHashService implements PasswordHashService {
    private final PasswordEncoder encoder;

    public MigratingPasswordHashService(PasswordEncoder encoder) {
        this.encoder = encoder;
    }

    @Override public String hash(String rawPassword) { return encoder.encode(rawPassword); }

    @Override public boolean matches(String rawPassword, String encodedPassword) {
        try { return encoder.matches(rawPassword, encodedPassword); }
        catch (IllegalArgumentException malformedHash) { return false; }
    }
}
