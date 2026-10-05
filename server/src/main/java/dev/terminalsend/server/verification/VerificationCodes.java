package dev.terminalsend.server.verification;

import dev.terminalsend.server.config.TerminalSendProperties;
import org.springframework.stereotype.Component;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.HexFormat;
import java.util.UUID;

/**
 * Generates 6-digit codes and hashes them with a server-side HMAC key, so a leaked table
 * cannot be brute-forced offline (a plain hash of 10^6 codes would be trivial to reverse).
 */
@Component
public class VerificationCodes {

    private final SecureRandom random = new SecureRandom();
    private final SecretKeySpec key;

    public VerificationCodes(TerminalSendProperties props) {
        // Domain-separated from JWT signing by the "verification|" message prefix below.
        this.key = new SecretKeySpec(props.jwt().secret().getBytes(StandardCharsets.UTF_8), "HmacSHA256");
    }

    public String newCode() {
        return "%06d".formatted(random.nextInt(1_000_000));
    }

    /** Binding the hash to the user prevents reusing a known code row for another account. */
    public String hash(UUID userId, String code) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(key);
            byte[] digest = mac.doFinal(("verification|" + userId + "|" + code).getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(digest);
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException(e);
        }
    }

    public boolean matches(UUID userId, String code, String expectedHash) {
        return MessageDigest.isEqual(
                hash(userId, code).getBytes(StandardCharsets.US_ASCII),
                expectedHash.getBytes(StandardCharsets.US_ASCII));
    }
}
