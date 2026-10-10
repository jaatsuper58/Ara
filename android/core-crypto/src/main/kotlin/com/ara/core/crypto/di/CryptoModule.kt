package com.ara.core.crypto.di

import com.ara.core.crypto.keystore.AraKeyManager
import com.ara.core.crypto.store.IdentityKeyStore
import com.ara.core.common.DefaultDispatcherProvider
import com.ara.core.common.DispatcherProvider
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import kotlinx.coroutines.CoroutineDispatcher
import javax.inject.Singleton

/**
 * Crypto bindings. The session/identity stores themselves are bound by
 * :core-database (Room-backed); this module binds the key manager.
 */
@Module
@InstallIn(SingletonComponent::class)
object CryptoModule {

    @Provides
    @Singleton
    fun provideIdentityKeyStore(keyManager: AraKeyManager): IdentityKeyStore = keyManager

    @Provides
    @Singleton
    fun provideDispatcherProvider(): DispatcherProvider = DefaultDispatcherProvider()

    @Provides
    fun provideIoDispatcher(provider: DispatcherProvider): CoroutineDispatcher = provider.io
}
