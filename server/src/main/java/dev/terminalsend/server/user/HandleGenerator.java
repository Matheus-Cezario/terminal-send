package dev.terminalsend.server.user;

import org.springframework.stereotype.Component;

import java.security.SecureRandom;

/** Generates public handles like {@code ts-7KQ2MX} (Crockford Base32: no I, L, O, U). */
@Component
public class HandleGenerator {

    static final String ALPHABET = "0123456789ABCDEFGHJKMNPQRSTVWXYZ";
    private static final int LENGTH = 6;
    private static final int MAX_TRIES = 10;

    private final SecureRandom random = new SecureRandom();
    private final UserRepository users;

    public HandleGenerator(UserRepository users) {
        this.users = users;
    }

    /** ~1 billion combinations; collisions are rare, so a bounded retry is enough. */
    public String newUniqueHandle() {
        for (int i = 0; i < MAX_TRIES; i++) {
            String handle = randomHandle();
            if (!users.existsByHandle(handle)) {
                return handle;
            }
        }
        throw new IllegalStateException("Could not generate a unique handle");
    }

    String randomHandle() {
        StringBuilder sb = new StringBuilder("ts-");
        for (int i = 0; i < LENGTH; i++) {
            sb.append(ALPHABET.charAt(random.nextInt(ALPHABET.length())));
        }
        return sb.toString();
    }
}
