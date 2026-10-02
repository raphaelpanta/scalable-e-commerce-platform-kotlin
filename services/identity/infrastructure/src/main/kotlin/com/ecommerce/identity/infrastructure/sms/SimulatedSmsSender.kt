package com.ecommerce.identity.infrastructure.sms

import com.ecommerce.identity.application.SmsSenderPort
import com.ecommerce.identity.domain.PhoneNumber
import jakarta.mail.MessagingException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.slf4j.Logger
import org.slf4j.LoggerFactory
import org.springframework.mail.MailException
import org.springframework.mail.javamail.JavaMailSender
import org.springframework.mail.javamail.MimeMessageHelper

/**
 * The SMS channel of the MVP (research section 12, docs/service-conventions.md section 8 "Phone verification code"):
 * nothing leaves the platform. Each SMS is mirrored to Mailpit exactly like the notification service's simulator: an
 * email to `sms-<E.164 digits>@sms.ecommerce.invalid`, subject `SMS to <phone>`, the SMS text as body, so tests and
 * the acceptance suite read the code by searching Mailpit for the number.
 *
 * Blocking exception (services/identity/README.md): JavaMail has no non-blocking API, so the send runs on
 * [dispatcher] (`Dispatchers.IO`), never on a Netty or Reactor event loop. Neither the number nor the text is logged.
 */
class SimulatedSmsSender(
    private val mail: JavaMailSender,
    private val from: String,
    private val dispatcher: CoroutineDispatcher = Dispatchers.IO,
) : SmsSenderPort {
    override suspend fun send(
        to: PhoneNumber,
        text: String,
    ): Boolean =
        try {
            withContext(dispatcher) {
                val message = mail.createMimeMessage()
                MimeMessageHelper(message, false, Charsets.UTF_8.name()).apply {
                    setFrom(from)
                    setTo(mirrorAddress(to))
                    setSubject("SMS to ${to.value}")
                    setText(text, false)
                }
                mail.send(message)
            }
            true
        } catch (failure: MailException) {
            refused(failure)
        } catch (failure: MessagingException) {
            refused(failure)
        }

    private fun refused(failure: Exception): Boolean {
        log.warn("The simulated SMS channel refused a message ({})", failure.javaClass.simpleName)
        return false
    }

    companion object {
        const val MIRROR_DOMAIN: String = "sms.ecommerce.invalid"
        private val log: Logger = LoggerFactory.getLogger(SimulatedSmsSender::class.java)

        /** `sms-<digits>@sms.ecommerce.invalid` for [phone]. */
        fun mirrorAddress(phone: PhoneNumber): String = "sms-${phone.value.removePrefix("+")}@$MIRROR_DOMAIN"
    }
}
