package dev.terminalsend.protocol.rest;

/** Stable error codes carried in the {@code code} field of RFC 9457 problem responses and WS error frames. */
public enum ErrorCode {
    VALIDATION_FAILED,
    EMAIL_ALREADY_REGISTERED,
    EMAIL_NOT_VERIFIED,
    INVALID_CREDENTIALS,
    INVALID_VERIFICATION_CODE,
    VERIFICATION_CODE_EXPIRED,
    TOO_MANY_ATTEMPTS,
    INVALID_REFRESH_TOKEN,
    NOT_CONNECTED,
    KEY_MISMATCH,
    KEY_NOT_FOUND,
    TOO_LARGE,
    RATE_LIMITED,
    NOT_FOUND,
    INTERNAL_ERROR
}
