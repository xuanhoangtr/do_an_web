package com.utewedding.app.exception;

/** Registration collided with an existing account. Callers answer with the generic accepted result. */
public class DuplicateRegistrationException extends RuntimeException {

    public DuplicateRegistrationException() {
        super("duplicate registration");
    }
}
