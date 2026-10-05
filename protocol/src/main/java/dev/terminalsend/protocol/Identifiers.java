package dev.terminalsend.protocol;

import java.util.Locale;
import java.util.regex.Pattern;

/** Validation helpers for the public identifiers a user can be invited by. */
public final class Identifiers {

    /** {@code ts-} followed by 6 Crockford Base32 characters (no I, L, O, U). */
    public static final Pattern HANDLE = Pattern.compile("^ts-[0-9A-HJKMNP-TV-Z]{6}$");

    /** Pragmatic email check; the server also relies on the verification code round-trip. */
    public static final Pattern EMAIL = Pattern.compile("^[^\\s@]{1,64}@[^\\s@]+\\.[^\\s@]{2,}$");

    public static final int MAX_EMAIL_LENGTH = 254;

    private Identifiers() {
    }

    public static String normalizeEmail(String email) {
        return email.strip().toLowerCase(Locale.ROOT);
    }

    public static String normalizeHandle(String handle) {
        return handle.strip().toUpperCase(Locale.ROOT).replaceFirst("^TS-", "ts-");
    }

    public static boolean isEmail(String value) {
        return value != null && value.length() <= MAX_EMAIL_LENGTH && EMAIL.matcher(value.strip()).matches();
    }

    public static boolean isHandle(String value) {
        return value != null && HANDLE.matcher(normalizeHandle(value)).matches();
    }
}
