package com.utewedding.app.exception;

/**
 * OTP mismatch only. Callers opt this exception out of transaction rollback so
 * the failed-attempt counter is committed.
 */
public class InvalidOtpException extends UnauthorizedException {

    public InvalidOtpException(String message) {
        super(message);
    }
}
