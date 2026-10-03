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
import com.ecommerce.identity.application.IdentityStore
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
import org.springframework.boot.context.properties.EnableConfigurationProperties
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration

/** Wires the use cases of the application module onto the adapters of [AdapterConfiguration]. */
@Suppress("TooManyFunctions") // one bean per use case
@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(IdentityProperties::class)
class UseCaseConfiguration {
    @Bean
    @Suppress("LongParameterList") // every port of the context
    fun identityStore(
        accounts: AccountRepository,
        addresses: AddressRepository,
        preferences: PreferenceRepository,
        tokens: TokenRepository,
        sessions: SessionRepository,
        throttles: ThrottleRepository,
        hasher: PasswordHasher,
        signer: TokenSigner,
        secrets: Secrets,
        sms: SmsSenderPort,
        events: IdentityEvents,
        transactions: Transactions,
        clock: Clock,
        properties: IdentityProperties,
    ): IdentityStore =
        IdentityStore(
            accounts,
            addresses,
            preferences,
            tokens,
            sessions,
            throttles,
            hasher,
            signer,
            secrets,
            sms,
            events,
            transactions,
            clock,
            properties.policies(),
        )

    @Bean
    fun registerAccount(store: IdentityStore): RegisterAccount = RegisterAccount(store)

    @Bean
    fun verifyEmail(store: IdentityStore): VerifyEmail = VerifyEmail(store)

    @Bean
    fun signIn(store: IdentityStore): SignIn = SignIn(store)

    @Bean
    fun refreshSession(store: IdentityStore): RefreshSession = RefreshSession(store)

    @Bean
    fun signOut(store: IdentityStore): SignOut = SignOut(store)

    @Bean
    fun requestPasswordReset(store: IdentityStore): RequestPasswordReset = RequestPasswordReset(store)

    @Bean
    fun completePasswordReset(store: IdentityStore): CompletePasswordReset = CompletePasswordReset(store)

    @Bean
    fun getProfile(store: IdentityStore): GetProfile = GetProfile(store)

    @Bean
    fun updateProfile(store: IdentityStore): UpdateProfile = UpdateProfile(store)

    @Bean
    fun deleteAccount(store: IdentityStore): DeleteAccount = DeleteAccount(store)

    @Bean
    fun listAddresses(store: IdentityStore): ListAddresses = ListAddresses(store)

    @Bean
    fun addAddress(store: IdentityStore): AddAddress = AddAddress(store)

    @Bean
    fun replaceAddress(store: IdentityStore): ReplaceAddress = ReplaceAddress(store)

    @Bean
    fun removeAddress(store: IdentityStore): RemoveAddress = RemoveAddress(store)

    @Bean
    fun getAccountAddress(store: IdentityStore): GetAccountAddress = GetAccountAddress(store)

    @Bean
    fun getNotificationPreferences(store: IdentityStore): GetNotificationPreferences = GetNotificationPreferences(store)

    @Bean
    fun updateNotificationPreferences(store: IdentityStore): UpdateNotificationPreferences =
        UpdateNotificationPreferences(store)

    @Bean
    fun requestPhoneVerification(store: IdentityStore): RequestPhoneVerification = RequestPhoneVerification(store)

    @Bean
    fun confirmPhoneVerification(store: IdentityStore): ConfirmPhoneVerification = ConfirmPhoneVerification(store)

    @Bean
    fun getAccountContact(store: IdentityStore): GetAccountContact = GetAccountContact(store)

    @Bean
    fun purgeRetainedData(
        retention: RetentionRepository,
        clock: Clock,
        properties: IdentityProperties,
    ): PurgeRetainedData = PurgeRetainedData(retention, clock, properties.retention.policy())
}
