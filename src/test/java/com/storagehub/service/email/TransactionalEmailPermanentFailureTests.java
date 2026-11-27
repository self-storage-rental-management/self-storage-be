package com.storagehub.service.email;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

/**
 * Gmail answered "550-5.4.5 Daily user sending limit exceeded" and the queue kept retrying the
 * same message five times, spending more of an allowance that was already gone. These cases pin
 * which failures must not be retried.
 */
class TransactionalEmailPermanentFailureTests {

    @Test
    void senderOverItsDailyLimitIsNotRetried() {
        assertThat(TransactionalEmailService.permanentFailure(
            "MailSendException: Failed messages: SMTPSendFailedException: 550-5.4.5 Daily user sending limit exceeded. "
                + "For more information on Gmail sending limits go to https://support.google.com/a/answer/166852"))
            .isTrue();
    }

    @Test
    void aRelayQuotaReplyIsNotRetried() {
        assertThat(TransactionalEmailService.permanentFailure(
            "SMTPSendFailedException: 550 5.7.1 Message rejected due to quota")).isTrue();
    }

    @Test
    void anUnknownMailboxIsNotRetried() {
        assertThat(TransactionalEmailService.permanentFailure(
            "SMTPSendFailedException: 550 5.1.1 User unknown")).isTrue();
        assertThat(TransactionalEmailService.permanentFailure(
            "SMTPSendFailedException: 550 5.1.1 Recipient address rejected: does not exist")).isTrue();
    }

    @Test
    void aTemporaryConnectionProblemIsRetried() {
        // The SMTP server being briefly unreachable recovers on its own, so retrying is correct.
        assertThat(TransactionalEmailService.permanentFailure(
            "MailConnectException: Couldn't connect to host, port: smtp.gmail.com, 587; timeout 5000")).isFalse();
        assertThat(TransactionalEmailService.permanentFailure(
            "MailSendException: 421 4.7.0 Try again later")).isFalse();
        assertThat(TransactionalEmailService.permanentFailure(null)).isFalse();
    }
}
