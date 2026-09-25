package com.utewedding.app.exception;

/** Authentication is intentionally blocked until the account owns its email address. */
public class EmailNotVerifiedException extends RuntimeException {
    public EmailNotVerifiedException() {
        super("EMAIL_NOT_VERIFIED: Vui lòng xác thực email trước khi đăng nhập.");
    }
}
