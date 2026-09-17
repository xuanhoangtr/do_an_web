package com.utewedding.app.service;

/** Shared password policy for API, web forms, and service-level enforcement. */
public final class PasswordPolicy {
    public static final int MIN_LENGTH = 10;
    public static final int MAX_LENGTH = 128;
    public static final String REGEX = "^(?=.*[a-z])(?=.*[A-Z])(?=.*\\d).+$";
    public static final String MESSAGE = "Mật khẩu phải có ít nhất 10 ký tự, gồm chữ thường, chữ hoa và số.";

    private PasswordPolicy() {
    }

    public static boolean isValid(String password) {
        return password != null && password.length() >= MIN_LENGTH && password.length() <= MAX_LENGTH
                && password.matches(REGEX);
    }

    public static void requireValid(String password) {
        if (!isValid(password)) throw new IllegalArgumentException(MESSAGE);
    }
}
