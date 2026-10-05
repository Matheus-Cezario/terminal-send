package dev.terminalsend.server.verification;

/** Published inside the issuing transaction; the email goes out only after commit. */
public record VerificationCodeIssued(String email, String code) {
}
