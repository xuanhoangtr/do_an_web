package com.utewedding.app.service.impl;

import com.utewedding.app.service.port.PasswordHashService;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;

@Service
@Deprecated(forRemoval = false)
public class BcryptPasswordHashService implements PasswordHashService {

    private final MigratingPasswordHashService delegate;

    public BcryptPasswordHashService(PasswordEncoder encoder) {
        this.delegate = new MigratingPasswordHashService(encoder);
    }

    @Override
    public String hash(String rawPassword) {
        return delegate.hash(rawPassword);
    }

    @Override
    public boolean matches(String rawPassword, String encodedPassword) {
        try {
            return delegate.matches(rawPassword, encodedPassword);
        } catch (IllegalArgumentException malformedHash) { return false; }
    }
}
