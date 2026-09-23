package com.utewedding.app.exception;

/** Registration collided with an existing username. */
public class DuplicateUsernameException extends DuplicateRegistrationException {
    public DuplicateUsernameException() {
        super();
    }
}
