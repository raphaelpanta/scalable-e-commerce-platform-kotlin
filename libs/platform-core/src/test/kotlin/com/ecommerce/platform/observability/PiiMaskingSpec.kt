package com.ecommerce.platform.observability

import ch.qos.logback.classic.Logger
import ch.qos.logback.classic.spi.ILoggingEvent
import ch.qos.logback.core.read.ListAppender
import com.ecommerce.platform.core.values.Email
import com.ecommerce.platform.core.values.PhoneNumber
import com.ecommerce.platform.core.values.PostalAddress
import com.ecommerce.platform.core.values.SecretToken
import com.ecommerce.platform.testing.PlatformArbs
import com.ecommerce.platform.testing.ProblemAssertions.shouldBeValid
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldNotContain
import io.kotest.property.checkAll
import org.slf4j.LoggerFactory

/** T117: personal data and tokens never reach the logs in clear when value objects are logged. */
class PiiMaskingSpec :
    FunSpec({
        val logger = LoggerFactory.getLogger("com.ecommerce.platform.pii-audit") as Logger
        val appender = ListAppender<ILoggingEvent>()

        beforeTest {
            appender.list.clear()
            appender.start()
            logger.addAppender(appender)
        }
        afterTest { logger.detachAppender(appender) }

        fun logged(): String =
            appender.list.joinToString("\n") { event ->
                event.formattedMessage + event.keyValuePairs.orEmpty().joinToString { "${it.key}=${it.value}" }
            }

        test("emails, phone numbers, addresses and tokens are masked in log lines") {
            val email = Email.of("ada.lovelace@example.com").shouldBeValid()
            val phone = PhoneNumber.of("+5511987654321").shouldBeValid()
            val address =
                PostalAddress
                    .of("Ada Lovelace", "12 Analytical Street", "Flat 2", "London", "England", "N1 9GU", "GB")
                    .shouldBeValid()
            val token = SecretToken.generate()

            logger.info("account {} reachable at {} ships to {} with token {}", email, phone, address, token)
            logger.info("interpolated $email $phone $address $token")
            logger
                .atInfo()
                .addKeyValue("email", email)
                .addKeyValue("address", address)
                .log("structured")

            val text = logged()
            listOf(
                "ada.lovelace",
                "5511987654321",
                "987654321",
                "Ada Lovelace",
                "Lovelace",
                "Analytical",
                "London",
                "England",
                "N1 9GU",
                token.value,
            ).forEach { secret -> text shouldNotContain secret }
            text shouldContain "a***@example.com"
            text shouldContain "***21"
            text shouldContain "countryCode=GB"
            text shouldContain "SecretToken(***)"
        }

        test("generated personal data never leaks through toString") {
            checkAll(
                PlatformArbs.email(),
                PlatformArbs.phoneNumber(),
                PlatformArbs.postalAddress(),
            ) { email, phone, address ->
                logger.info("{} {} {}", email, phone, address)
                val text = logged()
                val local = email.value.substringBefore('@')
                if (local.length > 1) text shouldNotContain email.value
                text shouldNotContain phone.value
                if (address.line1.length > 3) text shouldNotContain "line1=${address.line1}"
                appender.list.clear()
            }
        }

        test("personal value types are marked @Pii") {
            listOf(Email::class, PhoneNumber::class, PostalAddress::class, SecretToken::class).forEach { type ->
                type.java.isAnnotationPresent(Pii::class.java) shouldBe true
            }
        }

        test("masking rules") {
            PiiMasking.mask(null) shouldBe null
            PiiMasking.mask("") shouldBe "***"
            PiiMasking.mask("a") shouldBe "***"
            PiiMasking.mask("   ") shouldBe "***"
            PiiMasking.mask("Ada") shouldBe "A***"
            PiiMasking.maskEmail("ada@example.com") shouldBe "a***@example.com"
            PiiMasking.maskEmail("no-at-sign") shouldBe "***"
            PiiMasking.maskAllButLast("+5511987654321", 2) shouldBe "***21"
            PiiMasking.maskAllButLast("12", 2) shouldBe "***"
        }
    })
