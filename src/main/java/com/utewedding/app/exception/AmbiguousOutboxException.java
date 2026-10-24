package com.utewedding.app.exception;

public class AmbiguousOutboxException extends RuntimeException {
    public AmbiguousOutboxException(String message) { super(message); }
}