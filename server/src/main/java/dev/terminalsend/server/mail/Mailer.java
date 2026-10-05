package dev.terminalsend.server.mail;

/** Outbound email port; the SMTP adapter is the only production implementation. */
public interface Mailer {

    void send(String to, String subject, String textBody);
}
