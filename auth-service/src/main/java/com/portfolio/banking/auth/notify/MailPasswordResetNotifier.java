package com.portfolio.banking.auth.notify;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.mail.SimpleMailMessage;
import org.springframework.mail.MailException;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.stereotype.Component;

import java.time.Duration;

/**
 * Sends the reset link over SMTP. In this project that is Mailpit, a mail
 * catcher in {@code docker-compose.yml} that accepts everything and delivers
 * nothing - so the flow is genuinely end-to-end (a real message, over a real
 * protocol, readable at http://localhost:8025) without a single email ever
 * escaping.
 * <p>
 * The alternative for a project with no mail channel is to log the token,
 * and it is worth naming why that was rejected: a reset token is the
 * password for as long as it lives, and logs are the one place credentials
 * get copied, shipped and retained by default. One container avoids putting
 * an account takeover in a log file.
 * <p>
 * Failures are swallowed rather than propagated - see
 * {@link IPasswordResetNotifier}. If SMTP is down the caller must still get
 * the same answer it would have got otherwise, because any difference is a
 * way to ask whether an address is registered.
 */
@Component
public class MailPasswordResetNotifier implements IPasswordResetNotifier {

    private static final Logger log = LoggerFactory.getLogger(MailPasswordResetNotifier.class);

    private final JavaMailSender mailSender;
    private final String fromAddress;
    private final String resetUrlTemplate;
    private final Duration tokenTtl;

    public MailPasswordResetNotifier(JavaMailSender mailSender,
                                      @Value("${banking.password-reset.from-address}") String fromAddress,
                                      @Value("${banking.password-reset.url-template}") String resetUrlTemplate,
                                      @Value("${banking.password-reset.token-ttl-seconds}") long tokenTtlSeconds) {
        this.mailSender = mailSender;
        this.fromAddress = fromAddress;
        this.resetUrlTemplate = resetUrlTemplate;
        this.tokenTtl = Duration.ofSeconds(tokenTtlSeconds);
    }

    @Override
    public void sendPasswordReset(String email, String token) {
        SimpleMailMessage message = new SimpleMailMessage();
        message.setFrom(fromAddress);
        message.setTo(email);
        message.setSubject("Reset your banking platform password");
        message.setText("""
                Someone asked to reset the password for this address.

                %s

                The link is good once and expires in %d minutes. If this
                wasn't you, nothing has changed and you can ignore this.
                """.formatted(resetUrlTemplate.replace("{token}", token), tokenTtl.toMinutes()));

        try {
            mailSender.send(message);
        } catch (MailException mailIsDown) {
            // Deliberately without the token, and without the exception's
            // message if it were ever to echo the body back: the whole point
            // of not logging the token is undone by logging it on the error
            // path, which is the path most likely to be pasted into a ticket.
            log.error("Could not send a password reset to a registered address - the request was still "
                    + "accepted, so the caller cannot tell. Cause: {}", mailIsDown.getClass().getSimpleName());
        }
    }
}
