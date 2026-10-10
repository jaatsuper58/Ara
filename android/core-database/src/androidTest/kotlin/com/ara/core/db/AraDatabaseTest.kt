package com.ara.core.db

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.ara.core.db.entity.ChatEntity
import com.ara.core.db.entity.MessageEntity
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import net.zetetic.database.sqlcipher.SupportOpenHelperFactory
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/**
 * Phase 2 DoD: insert/query works against a SQLCipher-encrypted database, and
 * the on-disk file is NOT a plaintext SQLite database.
 */
@RunWith(AndroidJUnit4::class)
class AraDatabaseTest {

    private lateinit var db: AraDatabase
    private lateinit var dbFile: File

    @Before
    fun setUp() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        System.loadLibrary("sqlcipher")
        val passphrase = ByteArray(32) { it.toByte() } // test-only deterministic key
        dbFile = context.getDatabasePath("test_encrypted.db")
        dbFile.parentFile?.mkdirs()
        db = Room.databaseBuilder(context, AraDatabase::class.java, "test_encrypted.db")
            .openHelperFactory(SupportOpenHelperFactory(passphrase))
            .build()
    }

    @After
    fun tearDown() {
        db.close()
        dbFile.delete()
    }

    @Test
    fun insertAndQueryEncryptedRows() = runBlocking {
        val chat = ChatEntity(id = "dm_a_b", type = "DIRECT", title = "Alice")
        db.chatDao().upsert(chat)
        db.messageDao().insert(
            MessageEntity(
                id = "m1",
                chatId = "dm_a_b",
                senderId = "a",
                type = "TEXT",
                body = "top secret plaintext",
                timestamp = 1,
                status = "SENT",
            )
        )

        val messages = db.messageDao().observeChat("dm_a_b").first()
        assertEquals(1, messages.size)
        assertEquals("top secret plaintext", messages[0].body)

        val chats = db.chatDao().observeAll().first()
        assertEquals(1, chats.size)
    }

    @Test
    fun fullTextSearchFindsMessages() = runBlocking {
        db.chatDao().upsert(ChatEntity(id = "chat", type = "SAVED", title = "Notes"))
        db.messageDao().insert(
            MessageEntity(id = "m2", chatId = "chat", senderId = "me", type = "TEXT", body = "quarterly budget review notes", timestamp = 2, status = "RECEIVED")
        )
        val hits = db.messageDao().search("budget*")
        assertTrue(hits.isNotEmpty())
        assertEquals("quarterly budget review notes", hits[0].body)
    }

    @Test
    fun databaseFileIsNotPlaintextSqlite() = runBlocking {
        db.chatDao().upsert(ChatEntity(id = "x", type = "DIRECT", title = "x"))
        // Force a checkpoint to disk.
        db.messageDao().insert(
            MessageEntity(id = "m3", chatId = "x", senderId = "me", type = "TEXT", body = "row", timestamp = 3, status = "SENT")
        )
        val header = dbFile.inputStream().use { it.readNBytes(16) }
        val headerString = String(header, Charsets.US_ASCII)
        assertFalse("DB file must not be plaintext SQLite: $headerString", headerString.startsWith("SQLite format 3"))
    }
}
