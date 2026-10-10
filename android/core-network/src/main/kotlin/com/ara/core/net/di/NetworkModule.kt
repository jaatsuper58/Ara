package com.ara.core.net.di

import com.ara.core.common.AccountRepository
import com.ara.core.crypto.store.KeyBundleFetcher
import com.ara.core.net.BuildConfig
import com.ara.core.net.api.AuthApi
import com.ara.core.net.api.FilesApi
import com.ara.core.net.api.GroupsApi
import com.ara.core.net.auth.AuthInterceptor
import com.ara.core.net.auth.TokenAuthenticator
import com.ara.core.net.auth.TokenStore
import dagger.Binds
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import kotlinx.serialization.json.Json
import okhttp3.ConnectionSpec
import okhttp3.CertificatePinner
import okhttp3.OkHttpClient
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.TlsVersion
import retrofit2.Retrofit
import retrofit2.converter.kotlinx.serialization.asConverterFactory
import java.util.concurrent.TimeUnit
import javax.inject.Named
import javax.inject.Singleton

/**
 * Network provisioning.
 *
 * TLS: connections are restricted to modern TLS (1.3 negotiated in practice);
 * certificate pinning is enabled when CERT_PIN_SHA256 is provided via build
 * config (see docs/security.md §Checklist).
 */
@Module
@InstallIn(SingletonComponent::class)
object NetworkModule {

    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }

    @Provides
    @Singleton
    fun provideCertificatePinner(): CertificatePinner {
        val pin = BuildConfig.CERT_PIN_SHA256
        val builder = CertificatePinner.Builder()
        if (pin.isNotBlank()) {
            builder.add("**.ara.example", pin)
        }
        return builder.build()
    }

    @Provides
    @Singleton
    fun provideOkHttp(
        tokenStore: TokenStore,
        pinner: CertificatePinner,
    ): OkHttpClient = OkHttpClient.Builder()
        .connectionSpecs(listOf(ConnectionSpec.Builder(ConnectionSpec.RESTRICTED_TLS).tlsVersions(TlsVersion.TLS_1_3, TlsVersion.TLS_1_2).build()))
        .certificatePinner(pinner)
        .addInterceptor(AuthInterceptor(tokenStore))
        .authenticator(TokenAuthenticator(tokenStore))
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .writeTimeout(30, TimeUnit.SECONDS)
        .build()

    @Provides
    @Singleton
    @Named("ws")
    fun provideWsClient(base: OkHttpClient): OkHttpClient = base.newBuilder()
        .pingInterval(30, TimeUnit.SECONDS)
        .build()

    @Provides
    @Singleton
    @Named("wsUrl")
    fun provideWsUrl(): String = BuildConfig.WS_URL

    @Provides
    @Singleton
    fun provideRetrofit(client: OkHttpClient): Retrofit = Retrofit.Builder()
        .baseUrl(BuildConfig.API_BASE_URL)
        .client(client)
        .addConverterFactory(json.asConverterFactory("application/json".toMediaType()))
        .build()

    @Provides
    @Singleton
    fun provideAuthApi(retrofit: Retrofit): AuthApi = retrofit.create(AuthApi::class.java)

    @Provides
    @Singleton
    fun provideGroupsApi(retrofit: Retrofit): GroupsApi = retrofit.create(GroupsApi::class.java)
}

@Module
@InstallIn(SingletonComponent::class)
abstract class RepositoryBindingsModule {

    @Binds
    abstract fun bindAccountRepository(impl: TokenStore): AccountRepository
}
