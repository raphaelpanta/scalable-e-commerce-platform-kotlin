package com.ecommerce.notification.infrastructure.delivery

import com.ecommerce.notification.application.EmailSenderPort
import com.ecommerce.notification.application.OutgoingMessage
import com.ecommerce.notification.application.SendResult
import com.ecommerce.notification.application.SmsSenderPort
import com.ecommerce.notification.domain.DeliveryFailure
import com.ecommerce.notification.domain.FailureCategory
import com.ecommerce.notification.domain.RecipientAddress
import jakarta.mail.MessagingException
import jakarta.mail.internet.AddressException
import jakarta.mail.internet.MimeMessage
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.slf4j.Logger
import org.slf4j.LoggerFactory
import org.springframework.mail.MailAuthenticationException
import org.springframework.mail.MailException
import org.springframework.mail.MailParseException
import org.springframework.mail.MailSendException
import org.springframework.mail.javamail.JavaMailSender
import org.springframework.mail.javamail.MimeMessageHelper
import java.time.Instant
import java.util.concurrent.ConcurrentLinkedDeque

private val SMTP_REPLY = Regex("^([45][0-9]{2})[ -]")
private const val MAX_CAUSES = 20
private const val NOTIFICATION_HEADER = "X-Notification-Id"

/**
 * Sends MIME messages through [JavaMailSender] and turns every failure into a [SendResult.Failed] with a
 * client-safe reason: the SMTP reply code at most, never the server text (it may echo the address).
 *
 * Blocking exception (services/notification/README.md): JavaMail has no non-blocking API, so each send runs on
 * [Dispatchers.IO], never on a Netty or Reactor event loop; the delivery scheduler is its only caller.
 */
class MailDelivery(
    private val mail: JavaMailSender,
    private val from: String,
    private val io: CoroutineDispatcher = Dispatchers.IO,
) {
    suspend fun send(
        to: String,
        subject: String,
        body: String,
        notificationId: String,
    ): SendResult =
        try {
            withContext(io) {
                val message: MimeMessage = mail.createMimeMessage()
                MimeMessageHelper(message, false, Charsets.UTF_8.name()).apply {
                    setFrom(from)
                    setTo(to)
                    setSubject(subject)
                    setText(body, false)
                }
                message.setHeader(NOTIFICATION_HEADER, notificationId)
                mail.send(message)
            }
            SendResult.Delivered
        } catch (failure: MailException) {
            SendResult.Failed(failureOf(failure))
        } catch (failure: MessagingException) {
            SendResult.Failed(failureOf(failure))
        }

    companion object {
        private val log: Logger = LoggerFactory.getLogger(MailDelivery::class.java)

        /** The category and safe reason of a mail [failure]. */
        fun failureOf(failure: Exception): DeliveryFailure {
            val causes = related(failure)
            val reply = causes.firstNotNullOfOrNull(::replyCode)
            val result =
                when {
                    failure is MailParseException || causes.any { it is AddressException } -> {
                        DeliveryFailure(FailureCategory.INVALID_RECIPIENT, "The recipient address is invalid.")
                    }

                    failure is MailAuthenticationException -> {
                        DeliveryFailure(FailureCategory.CHANNEL_UNAVAILABLE, "SMTP authentication failed.")
                    }

                    reply != null -> {
                        DeliveryFailure(categoryOf(reply), "SMTP server refused the message ($reply).")
                    }

                    else -> {
                        DeliveryFailure(FailureCategory.CHANNEL_UNAVAILABLE, "SMTP server unreachable.")
                    }
                }
            log.warn("Mail delivery failed: {} ({})", result.reason, failure.javaClass.simpleName)
            return result
        }

        /** 5xx is a permanent refusal by the server, 4xx a temporary one. */
        private fun categoryOf(reply: String): FailureCategory =
            if (reply.startsWith('5')) FailureCategory.REJECTED_BY_PROVIDER else FailureCategory.CHANNEL_UNAVAILABLE

        /** The SMTP reply code a failure message starts a line with, if any. */
        private fun replyCode(failure: Throwable): String? =
            failure.message
                ?.lines()
                ?.firstNotNullOfOrNull { SMTP_REPLY.find(it.trim()) }
                ?.groupValues
                ?.get(1)

        /**
         * [failure], its causes, the per-message exceptions of a [MailSendException] and the chained
         * `nextException`s of JavaMail (where the reply to a refused recipient lives), at most [MAX_CAUSES].
         */
        private fun related(failure: Throwable): List<Throwable> {
            val seen = mutableListOf<Throwable>()
            val pending = ArrayDeque(listOf(failure))
            while (pending.isNotEmpty() && seen.size < MAX_CAUSES) {
                val next = pending.removeFirst()
                if (seen.none { it === next }) {
                    seen += next
                    listOfNotNull(next.cause, (next as? MessagingException)?.nextException).forEach(pending::addLast)
                    (next as? MailSendException)?.messageExceptions?.forEach(pending::addLast)
                }
            }
            return seen
        }
    }
}

/** The email channel: one message per notification, from the configured sender address. */
class SmtpEmailSender(
    private val delivery: MailDelivery,
) : EmailSenderPort {
    override suspend fun send(message: OutgoingMessage): SendResult =
        delivery.send(
            message.to.value,
            message.content.subject,
            message.content.body,
            message.notificationId.toString(),
        )
}

/** One SMS as the simulator recorded it. Personal data: `toString()` hides the number and the text. */
class SimulatedSms(
    val to: RecipientAddress,
    val text: String,
    val at: Instant,
) {
    override fun toString(): String = "SimulatedSms(at=$at)"
}

/**
 * The SMS channel of the MVP (research §12): nothing leaves the platform. Each message is mirrored to Mailpit as
 * an email to `sms-<E.164 digits>@sms.ecommerce.invalid` with the subject `SMS to <phone>` and the SMS text as
 * body (docs/service-conventions.md §8), then recorded; a failed mirror is a failed attempt, retried like email.
 */
class SimulatedSmsSender(
    private val delivery: MailDelivery,
    private val capacity: Int = DEFAULT_CAPACITY,
) : SmsSenderPort {
    private val recorded = ConcurrentLinkedDeque<SimulatedSms>()

    /** The messages sent so far, oldest first (the last [capacity] ones). */
    val messages: List<SimulatedSms> get() = recorded.toList()

    override suspend fun send(message: OutgoingMessage): SendResult {
        val phone = message.to.value
        val result =
            delivery.send(
                mirrorAddress(phone),
                "SMS to $phone",
                message.content.body,
                message.notificationId.toString(),
            )
        if (result == SendResult.Delivered) {
            recorded.addLast(SimulatedSms(message.to, message.content.body, Instant.now()))
            while (recorded.size > capacity) recorded.pollFirst()
        }
        return result
    }

    companion object {
        const val DEFAULT_CAPACITY: Int = 1000
        const val MIRROR_DOMAIN: String = "sms.ecommerce.invalid"

        /** `sms-<digits>@sms.ecommerce.invalid` for the E.164 number [phone]. */
        fun mirrorAddress(phone: String): String = "sms-${phone.removePrefix("+")}@$MIRROR_DOMAIN"
    }
}
