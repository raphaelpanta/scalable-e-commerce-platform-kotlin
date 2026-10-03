package com.ecommerce.identity.infrastructure

import com.ecommerce.identity.application.AccountRepository
import com.ecommerce.identity.application.AddAddress
import com.ecommerce.identity.application.AddressRepository
import com.ecommerce.identity.application.Clock
import com.ecommerce.identity.application.CompletePasswordReset
import com.ecommerce.identity.application.ConfirmPhoneVerification
import com.ecommerce.identity.application.DeleteAccount
import com.ecommerce.identity.application.GetAccountAddress
import com.ecommerce.identity.application.GetAccountContact
import com.ecommerce.identity.application.GetNotificationPreferences
import com.ecommerce.identity.application.GetProfile
import com.ecommerce.identity.application.IdentityEvents
import com.ecommerce.identity.application.ListAddresses
import com.ecommerce.identity.application.PasswordHasher
import com.ecommerce.identity.application.PreferenceRepository
import com.ecommerce.identity.application.PurgeRetainedData
import com.ecommerce.identity.application.RefreshSession
import com.ecommerce.identity.application.RegisterAccount
import com.ecommerce.identity.application.RemoveAddress
import com.ecommerce.identity.application.ReplaceAddress
import com.ecommerce.identity.application.RequestPasswordReset
import com.ecommerce.identity.application.RequestPhoneVerification
import com.ecommerce.identity.application.RetentionRepository
import com.ecommerce.identity.application.Secrets
import com.ecommerce.identity.application.SessionRepository
import com.ecommerce.identity.application.SignIn
import com.ecommerce.identity.application.SignOut
import com.ecommerce.identity.application.SmsSenderPort
import com.ecommerce.identity.application.ThrottleRepository
import com.ecommerce.identity.application.TokenRepository
import com.ecommerce.identity.application.TokenSigner
import com.ecommerce.identity.application.Transactions
import com.ecommerce.identity.application.UpdateNotificationPreferences
import com.ecommerce.identity.application.UpdateProfile
import com.ecommerce.identity.application.VerifyEmail
import com.ecommerce.identity.infrastructure.jobs.IdentityPurgeJob
import com.ecommerce.identity.infrastructure.messaging.IdentityEnvelopes
import com.ecommerce.identity.infrastructure.messaging.IdentityEventPublisher
import com.ecommerce.identity.infrastructure.persistence.R2dbcAccountRepository
import com.ecommerce.identity.infrastructure.persistence.R2dbcAddressRepository
import com.ecommerce.identity.infrastructure.persistence.R2dbcPreferenceRepository
import com.ecommerce.identity.infrastructure.persistence.R2dbcRetentionRepository
import com.ecommerce.identity.infrastructure.persistence.R2dbcSessionRepository
import com.ecommerce.identity.infrastructure.persistence.R2dbcThrottleRepository
import com.ecommerce.identity.infrastructure.persistence.R2dbcTokenRepository
import com.ecommerce.identity.infrastructure.persistence.ReactiveTransactions
import com.ecommerce.identity.infrastructure.security.Argon2idPasswordHasher
import com.ecommerce.identity.infrastructure.security.JwtTokenSigner
import com.ecommerce.identity.infrastructure.security.KeyRingJwtProcessor
import com.ecommerce.identity.infrastructure.security.SecureSecrets
import com.ecommerce.identity.infrastructure.security.SigningKey
import com.ecommerce.identity.infrastructure.security.SigningKeyRing
import com.ecommerce.identity.infrastructure.security.SystemClock
import com.ecommerce.identity.infrastructure.sms.SimulatedSmsSender
import com.ecommerce.identity.infrastructure.web.AddressHandlers
import com.ecommerce.identity.infrastructure.web.InternalHandlers
import com.ecommerce.identity.infrastructure.web.JwksHandler
import com.ecommerce.identity.infrastructure.web.PasswordResetHandlers
import com.ecommerce.identity.infrastructure.web.PreferenceHandlers
import com.ecommerce.identity.infrastructure.web.ProfileHandlers
import com.ecommerce.identity.infrastructure.web.RegistrationHandlers
import com.ecommerce.identity.infrastructure.web.SessionHandlers
import com.ecommerce.platform.messaging.envelope.EnvelopeFactory
import com.ecommerce.platform.messaging.outbox.OutboxPublisher
import com.ecommerce.platform.security.PlatformSecurityAutoConfiguration
import com.ecommerce.platform.security.PlatformSecurityProperties
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.core.env.Environment
import org.springframework.core.env.Profiles
import org.springframework.mail.javamail.JavaMailSender
import org.springframework.r2dbc.core.DatabaseClient
import org.springframework.security.oauth2.jwt.NimbusReactiveJwtDecoder
import org.springframework.security.oauth2.jwt.ReactiveJwtDecoder
import org.springframework.transaction.ReactiveTransactionManager
import org.springframework.transaction.reactive.TransactionalOperator
import java.time.Clock as JavaClock

/** The Spring profiles in which a missing signing key is generated at start-up instead of refused (FR-024). */
private const val KEY_GENERATING_PROFILES = "dev | test"

/** The adapters around the use cases: persistence, keys and tokens, hashing, SMS, outbox, web handlers. */
@Suppress("TooManyFunctions") // one bean per adapter
@Configuration(proxyBeanMethods = false)
class AdapterConfiguration {
    @Bean
    fun javaClock(): JavaClock = JavaClock.systemUTC()

    @Bean
    fun identityClock(javaClock: JavaClock): Clock = SystemClock(javaClock)

    @Bean
    fun identityTransactionalOperator(transactionManager: ReactiveTransactionManager): TransactionalOperator =
        TransactionalOperator.create(transactionManager)

    @Bean
    fun transactions(identityTransactionalOperator: TransactionalOperator): Transactions =
        ReactiveTransactions(identityTransactionalOperator)

    @Bean
    fun accountRepository(
        database: DatabaseClient,
        identityTransactionalOperator: TransactionalOperator,
    ): AccountRepository = R2dbcAccountRepository(database, identityTransactionalOperator)

    @Bean
    fun addressRepository(
        database: DatabaseClient,
        identityTransactionalOperator: TransactionalOperator,
    ): AddressRepository = R2dbcAddressRepository(database, identityTransactionalOperator)

    @Bean
    fun preferenceRepository(database: DatabaseClient): PreferenceRepository = R2dbcPreferenceRepository(database)

    @Bean
    fun tokenRepository(
        database: DatabaseClient,
        identityTransactionalOperator: TransactionalOperator,
    ): TokenRepository = R2dbcTokenRepository(database, identityTransactionalOperator)

    @Bean
    fun sessionRepository(
        database: DatabaseClient,
        identityTransactionalOperator: TransactionalOperator,
    ): SessionRepository = R2dbcSessionRepository(database, identityTransactionalOperator)

    @Bean
    fun throttleRepository(
        database: DatabaseClient,
        identityTransactionalOperator: TransactionalOperator,
        javaClock: JavaClock,
    ): ThrottleRepository = R2dbcThrottleRepository(database, identityTransactionalOperator, javaClock)

    @Bean
    fun retentionRepository(database: DatabaseClient): RetentionRepository = R2dbcRetentionRepository(database)

    @Bean
    fun identityPurgeJob(
        purgeRetainedData: PurgeRetainedData,
        properties: IdentityProperties,
    ): IdentityPurgeJob = IdentityPurgeJob(purgeRetainedData, properties.retention.purgeInterval)

    /**
     * The signing key shared by every instance: `identity.signing-key`; generated at start-up only under the profile
     * `dev` or `test`, otherwise a missing key stops the start-up (FR-024).
     */
    @Bean
    fun signingKeyRing(
        properties: IdentityProperties,
        environment: Environment,
    ): SigningKeyRing {
        val generationAllowed = environment.acceptsProfiles(Profiles.of(KEY_GENERATING_PROFILES))
        val key =
            SigningKey.configured(properties.signingKey, properties.signingKeyId.ifBlank { null }, generationAllowed)
        return SigningKeyRing(listOf(key))
    }

    @Bean
    fun tokenSigner(
        ring: SigningKeyRing,
        security: PlatformSecurityProperties,
    ): TokenSigner = JwtTokenSigner(ring, security.issuer, security.audience)

    /** Identity validates its own tokens against its key ring (no HTTP call to its own JWKS). */
    @Bean
    fun identityJwtDecoder(
        ring: SigningKeyRing,
        security: PlatformSecurityProperties,
    ): ReactiveJwtDecoder =
        NimbusReactiveJwtDecoder(KeyRingJwtProcessor(ring)).apply {
            setJwtValidator(PlatformSecurityAutoConfiguration.tokenValidator(security))
        }

    @Bean
    fun passwordHasher(): PasswordHasher = Argon2idPasswordHasher()

    @Bean
    fun secrets(): Secrets = SecureSecrets()

    @Bean
    fun smsSender(
        mail: JavaMailSender,
        properties: IdentityProperties,
    ): SmsSenderPort = SimulatedSmsSender(mail, properties.sms.from)

    @Bean
    fun identityEnvelopes(envelopes: EnvelopeFactory): IdentityEnvelopes = IdentityEnvelopes(envelopes)

    @Bean
    fun identityEvents(
        outbox: OutboxPublisher,
        envelopes: IdentityEnvelopes,
    ): IdentityEvents = IdentityEventPublisher(outbox, envelopes)

    @Bean
    fun registrationHandlers(
        registerAccount: RegisterAccount,
        verifyEmail: VerifyEmail,
    ): RegistrationHandlers = RegistrationHandlers(registerAccount, verifyEmail)

    @Bean
    fun sessionHandlers(
        signIn: SignIn,
        refreshSession: RefreshSession,
        signOut: SignOut,
    ): SessionHandlers = SessionHandlers(signIn, refreshSession, signOut)

    @Bean
    fun passwordResetHandlers(
        requestPasswordReset: RequestPasswordReset,
        completePasswordReset: CompletePasswordReset,
    ): PasswordResetHandlers = PasswordResetHandlers(requestPasswordReset, completePasswordReset)

    @Bean
    fun profileHandlers(
        getProfile: GetProfile,
        updateProfile: UpdateProfile,
        deleteAccount: DeleteAccount,
    ): ProfileHandlers = ProfileHandlers(getProfile, updateProfile, deleteAccount)

    @Bean
    fun addressHandlers(
        listAddresses: ListAddresses,
        addAddress: AddAddress,
        replaceAddress: ReplaceAddress,
        removeAddress: RemoveAddress,
    ): AddressHandlers = AddressHandlers(listAddresses, addAddress, replaceAddress, removeAddress)

    @Bean
    fun preferenceHandlers(
        getNotificationPreferences: GetNotificationPreferences,
        updateNotificationPreferences: UpdateNotificationPreferences,
        requestPhoneVerification: RequestPhoneVerification,
        confirmPhoneVerification: ConfirmPhoneVerification,
    ): PreferenceHandlers =
        PreferenceHandlers(
            getNotificationPreferences,
            updateNotificationPreferences,
            requestPhoneVerification,
            confirmPhoneVerification,
        )

    @Bean
    fun internalHandlers(
        getAccountAddress: GetAccountAddress,
        getAccountContact: GetAccountContact,
    ): InternalHandlers = InternalHandlers(getAccountAddress, getAccountContact)

    @Bean
    fun jwksHandler(ring: SigningKeyRing): JwksHandler = JwksHandler(ring)
}
