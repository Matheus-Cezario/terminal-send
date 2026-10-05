package dev.terminalsend.client.crypto;

/** Authentication failed: wrong key, wrong password, or the data was tampered with. */
public class DecryptionException extends Exception {

    private static final long serialVersionUID = 1L;

    public DecryptionException(String message, Throwable cause) {
        super(message, cause);
    }
}
