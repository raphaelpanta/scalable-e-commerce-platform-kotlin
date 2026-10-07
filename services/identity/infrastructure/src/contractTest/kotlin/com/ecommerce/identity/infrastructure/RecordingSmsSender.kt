package com.ecommerce.identity.infrastructure

import com.ecommerce.identity.application.SmsSenderPort
import com.ecommerce.identity.domain.PhoneNumber
import org.springframework.boot.test.context.TestConfiguration
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Primary
import java.util.concurrent.ConcurrentLinkedQueue

/**
 * The contract context has no SMTP server for the simulated SMS channel, so the SMS port records the messages it is
 * asked to send and always accepts them (the storefront pact's `requestPhoneVerification` state answers 202).
 */
class RecordingSmsSender : SmsSenderPort {
    val sent = ConcurrentLinkedQueue<Pair<PhoneNumber, String>>()

    override suspend fun send(
        to: PhoneNumber,
        text: String,
    ): Boolean {
        sent.add(to to text)
        return true
    }
}

@TestConfiguration(proxyBeanMethods = false)
class RecordingSmsConfig {
    @Bean
    @Primary
    fun recordingSmsSender(): RecordingSmsSender = RecordingSmsSender()
}
