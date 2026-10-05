package dev.terminalsend.server.verification;

import dev.terminalsend.server.config.TerminalSendProperties;
import dev.terminalsend.server.mail.Mailer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionalEventListener;

@Component
class VerificationMailListener {

    private static final Logger log = LoggerFactory.getLogger(VerificationMailListener.class);

    private final Mailer mailer;
    private final long ttlMinutes;

    VerificationMailListener(Mailer mailer, TerminalSendProperties props) {
        this.mailer = mailer;
        this.ttlMinutes = props.verification().codeTtl().toMinutes();
    }

    @TransactionalEventListener
    void on(VerificationCodeIssued event) {
        try {
            mailer.send(event.email(), "Seu código terminal-send: " + event.code(), """
                    Seu código de verificação do terminal-send é:

                        %s

                    Ele expira em %d minutos. Se você não pediu este código, ignore este email.
                    """.formatted(event.code(), ttlMinutes));
        } catch (RuntimeException e) {
            // The code is already stored; the user can ask for a resend.
            log.warn("Failed to send verification email", e);
        }
    }
}
