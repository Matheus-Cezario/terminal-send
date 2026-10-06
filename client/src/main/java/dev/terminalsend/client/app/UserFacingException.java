package dev.terminalsend.client.app;

/** An expected problem with a message meant to be shown to the user as is (Portuguese). */
public class UserFacingException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    public UserFacingException(String message) {
        super(message);
    }
}
