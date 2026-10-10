package com.ara.core.crypto

import com.ara.core.crypto.session.SafetyNumber
import com.ara.core.crypto.session.SessionCipher
import com.ara.core.crypto.wire.WireJson
import com.ara.core.crypto.wire.WireMessage
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SessionCipherTest {

    private fun buildCipherPair(
        aliceId: String,
        bobId: String,
        directory: FakeBundleDirectory,
        aliceStore: FakeIdentityKeyStore,
        bobStore: FakeIdentityKeyStore,
    ): Pair<SessionCipher, SessionCipher> {
        // Bootstrap both sides so the directory can serve bundles.
        runTest { aliceStore.identity(); bobStore.identity(); bobStore.currentSignedPreKey(); bobStore.addOneTimePreKeys(20) }

        val aliceSessions = FakeSessionStore()
        val bobSessions = FakeSessionStore()
        val aliceCipher = SessionCipher(
            sessionStore = aliceSessions,
            identityStore = aliceStore,
            remoteIdentityStore = FakeRemoteIdentityStore(),
            bundleFetcher = directory.fetcher,
            io = kotlinx.coroutines.Dispatchers.Unconfined,
        )
        val bobCipher = SessionCipher(
            sessionStore = bobSessions,
            identityStore = bobStore,
            remoteIdentityStore = FakeRemoteIdentityStore(),
            bundleFetcher = directory.fetcher,
            io = kotlinx.coroutines.Dispatchers.Unconfined,
        )
        return aliceCipher to bobCipher
    }

    @Test
    fun `prekey handshake and conversation round trip`() = runTest {
        val directory = FakeBundleDirectory()
        val aliceStore = FakeIdentityKeyStore()
        val bobStore = FakeIdentityKeyStore()
        directory.register("bob", bobStore)
        directory.register("alice", aliceStore)
        val (alice, bob) = buildCipherPair("alice", "bob", directory, aliceStore, bobStore)

        val first = alice.encrypt("bob", "hello bob".toByteArray())
        assertTrue("first message must be a prekey message", first is WireMessage.PreKeyWire)

        // Serialize across the "wire" (JSON) to prove wire-compatibility.
        val json = WireJson.json.encodeToString(WireMessage.serializer(), first)
        val decoded = WireJson.json.decodeFromString(WireMessage.serializer(), json)

        val plaintext = bob.decrypt("alice", decoded)
        assertArrayEquals("hello bob".toByteArray(), plaintext)

        // Reply — now a plain ratchet message.
        val reply = bob.encrypt("alice", "hi alice".toByteArray())
        assertTrue(reply is WireMessage.RatchetWire)
        assertArrayEquals("hi alice".toByteArray(), alice.decrypt("bob", reply))
    }

    @Test
    fun `long conversation over persisted sessions`() = runTest {
        val directory = FakeBundleDirectory()
        val aliceStore = FakeIdentityKeyStore()
        val bobStore = FakeIdentityKeyStore()
        directory.register("bob", bobStore)
        directory.register("alice", aliceStore)
        val (alice, bob) = buildCipherPair("alice", "bob", directory, aliceStore, bobStore)

        repeat(20) { i ->
            val m = alice.encrypt("bob", "a$i".toByteArray())
            assertArrayEquals("a$i".toByteArray(), bob.decrypt("alice", m))
            val r = bob.encrypt("alice", "b$i".toByteArray())
            assertArrayEquals("b$i".toByteArray(), alice.decrypt("bob", r))
        }
    }

    @Test
    fun `out of order within one chain`() = runTest {
        val directory = FakeBundleDirectory()
        val aliceStore = FakeIdentityKeyStore()
        val bobStore = FakeIdentityKeyStore()
        directory.register("bob", bobStore)
        directory.register("alice", aliceStore)
        val (alice, bob) = buildCipherPair("alice", "bob", directory, aliceStore, bobStore)

        // The very first message carries the X3DH handshake and establishes the
        // session; out-of-order delivery applies only within an established chain.
        val boot = alice.encrypt("bob", "boot".toByteArray())
        bob.decrypt("alice", boot)

        val m1 = alice.encrypt("bob", "one".toByteArray())
        val m2 = alice.encrypt("bob", "two".toByteArray())

        bob.decrypt("alice", m2)
        assertArrayEquals("one".toByteArray(), bob.decrypt("alice", m1))
    }
}

class SafetyNumberTest {

    @Test
    fun `same pair produces the same number regardless of order`() {
        val a = ByteArray(32) { it.toByte() }
        val b = ByteArray(32) { (it + 5).toByte() }

        val ab = SafetyNumber.compute("alice", a, "bob", b)
        val ba = SafetyNumber.compute("bob", b, "alice", a)

        assertEquals(ab, ba)
        assertEquals(65, ab.length) // 60 digits + 5 separators
        assertFalse(ab.any { !it.isDigit() && it != ' ' })
    }

    @Test
    fun `different keys produce different numbers`() {
        val a = ByteArray(32) { it.toByte() }
        val b = ByteArray(32) { (it + 1).toByte() }
        val c = ByteArray(32) { (it + 2).toByte() }
        assertTrue(SafetyNumber.compute("a", a, "b", b) != SafetyNumber.compute("a", a, "b", c))
    }
}
