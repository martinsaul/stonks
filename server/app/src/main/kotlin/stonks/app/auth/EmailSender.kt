package stonks.app.auth

import org.slf4j.LoggerFactory

/** Delivers one-time passcodes. */
fun interface EmailSender {
    fun sendOtp(email: String, code: String)
}

/**
 * Development stand-in that only logs the code.
 *
 * TODO: replace with real delivery (in-house mail service, SES, …) before launch.
 */
class LoggingEmailSender : EmailSender {
    private val log = LoggerFactory.getLogger(LoggingEmailSender::class.java)

    override fun sendOtp(email: String, code: String) {
        log.info("OTP for {}: {} (dummy sender, no email was sent)", email, code)
    }
}
