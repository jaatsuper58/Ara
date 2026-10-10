package com.ara.core.db.di

import android.content.Context
import androidx.room.Room
import com.ara.core.crypto.store.RemoteIdentityStore
import com.ara.core.crypto.store.SenderKeyStore
import com.ara.core.crypto.store.SessionStore
import com.ara.core.db.AraDatabase
import com.ara.core.db.DatabaseKeyManager
import com.ara.core.db.dao.KeyDao
import com.ara.core.db.entity.CryptoSessionEntity
import com.ara.core.db.entity.RemoteIdentityEntity
import com.ara.core.db.entity.SenderKeyCoverageEntity
import com.ara.core.db.entity.SenderKeyEntity
import com.ara.core.db.store.RoomRemoteIdentityStore
import com.ara.core.db.store.RoomSenderKeyStore
import com.ara.core.db.store.RoomSessionStore
import dagger.Binds
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
object DatabaseModule {

    @Provides
    @Singleton
    fun provideDatabase(
        @ApplicationContext context: Context,
        keyManager: DatabaseKeyManager,
    ): AraDatabase {
        // SQLCipher's native library must be loaded before the factory is used.
        System.loadLibrary("sqlcipher")
        val factory = net.zetetic.database.sqlcipher.SupportOpenHelperFactory(keyManager.getOrCreatePassphrase())
        return Room.databaseBuilder(context, AraDatabase::class.java, "ara.db")
            .openHelperFactory(factory)
            .build()
    }

    @Provides fun chatDao(db: AraDatabase) = db.chatDao()
    @Provides fun messageDao(db: AraDatabase) = db.messageDao()
    @Provides fun attachmentDao(db: AraDatabase) = db.attachmentDao()
    @Provides fun contactDao(db: AraDatabase) = db.contactDao()
    @Provides fun callLogDao(db: AraDatabase) = db.callLogDao()
    @Provides fun keyDao(db: AraDatabase) = db.keyDao()
}

@Module
@InstallIn(SingletonComponent::class)
abstract class StoreBindingsModule {

    @Binds
    @Singleton
    abstract fun bindSessionStore(impl: RoomSessionStore): SessionStore

    @Binds
    @Singleton
    abstract fun bindRemoteIdentityStore(impl: RoomRemoteIdentityStore): RemoteIdentityStore

    @Binds
    @Singleton
    abstract fun bindSenderKeyStore(impl: RoomSenderKeyStore): SenderKeyStore
}
