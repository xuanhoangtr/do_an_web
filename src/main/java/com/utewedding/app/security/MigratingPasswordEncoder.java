package com.utewedding.app.security;

import org.springframework.security.crypto.argon2.Argon2PasswordEncoder;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;

/**
 * Reads both password formats found during the NestJS-to-Spring migration.
 * Passwords created by this application are always written as Argon2id.
 */
public final class MigratingPasswordEncoder implements PasswordEncoder {

    private final PasswordEncoder argon2;
    private final BCryptPasswordEncoder bcrypt = new BCryptPasswordEncoder();

    public MigratingPasswordEncoder() {
        this(new Argon2PasswordEncoder(16, 32, 1, 19_456, 2));
    }

    public MigratingPasswordEncoder(PasswordEncoder argon2) {
        this.argon2 = argon2;
    }

    @Override
    public String encode(CharSequence rawPassword) {
        return argon2.encode(rawPassword);
    }

    @Override
    public boolean matches(CharSequence rawPassword, String encodedPassword) {
        if (encodedPassword == null || encodedPassword.isBlank()) {
            return false;
        }
        try {
            if (isBcrypt(encodedPassword)) {
                return bcrypt.matches(rawPassword, encodedPassword);
            }
            return argon2.matches(rawPassword, encodedPassword);
        } catch (IllegalArgumentException exception) {
            return false;
        }
    }

    @Override
    public boolean upgradeEncoding(String encodedPassword) {
        return encodedPassword == null || isBcrypt(encodedPassword)
                || argon2.upgradeEncoding(encodedPassword);
    }

    private static boolean isBcrypt(String value) {
        return value.startsWith("$2a$") || value.startsWith("$2b$") || value.startsWith("$2y$");
    }
}
