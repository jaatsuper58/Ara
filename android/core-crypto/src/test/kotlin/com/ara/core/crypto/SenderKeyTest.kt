package com.ara.core.crypto

import com.ara.core.crypto.group.SenderKeyCodec
import com.ara.core.crypto.wire.SenderKeyDistribution
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class SenderKeyTest {

    @Test
    fun `distribution enables decryption of group messages`() {
        val key = SenderKeyCodec.createKey("group1")
        val distribution = SenderKeyCodec.toDistribution(key)

        val wire = SenderKeyCodec.encrypt(key, "hello group".toByteArray())
        val plaintext = SenderKeyCodec.decrypt(distribution, wire)

        assertArrayEquals("hello group".toByteArray(), plaintext)
    }

    @Test
    fun `iteration derives distinct message keys`() {
        val key = SenderKeyCodec.createKey("group1")
        val distribution = SenderKeyCodec.toDistribution(key)

        val wire0 = SenderKeyCodec.encrypt(key, "msg0".toByteArray())
        val wire1 = SenderKeyCodec.encrypt(key, "msg1".toByteArray())

        assertEquals(0, wire0.iteration)
        assertEquals(1, wire1.iteration)
        assertArrayEquals("msg0".toByteArray(), SenderKeyCodec.decrypt(distribution, wire0))
        assertArrayEquals("msg1".toByteArray(), SenderKeyCodec.decrypt(distribution, wire1))
    }

    @Test
    fun `messages decrypt out of order`() {
        val key = SenderKeyCodec.createKey("group1")
        val distribution = SenderKeyCodec.toDistribution(key)
        val w1 = SenderKeyCodec.encrypt(key, "first".toByteArray())
        val w2 = SenderKeyCodec.encrypt(key, "second".toByteArray())
        assertArrayEquals("second".toByteArray(), SenderKeyCodec.decrypt(distribution, w2))
        assertArrayEquals("first".toByteArray(), SenderKeyCodec.decrypt(distribution, w1))
    }

    @Test
    fun `two senders keys are independent`() {
        val aliceKey = SenderKeyCodec.createKey("group1")
        val bobKey = SenderKeyCodec.createKey("group1")
        val wireA = SenderKeyCodec.encrypt(aliceKey, "from alice".toByteArray())
        val wireB = SenderKeyCodec.encrypt(bobKey, "from bob".toByteArray())

        val bobDist = SenderKeyCodec.toDistribution(bobKey)
        var failed = false
        try {
            SenderKeyCodec.decrypt(bobDist, wireA)
        } catch (expected: Exception) {
            failed = true
        }
        assertEquals(true, failed)
        assertArrayEquals("from bob".toByteArray(), SenderKeyCodec.decrypt(bobDist, wireB))
    }
}

class SenderKeyStoreTest {

    @Test
    fun `ensureOwned tracks member coverage`() = runTest {
        val store = FakeSenderKeyStore()
        val first = com.ara.core.crypto.group.SenderKeyCodec.createKey("g")
        store.saveOwned(first)

        val distribution = ensureOwned(store, "g", listOf("alice", "bob", "carol"))
        assertEquals("alice,bob,carol", distribution.joinToString(",") { it })
        val again = ensureOwned(store, "g", listOf("alice", "bob", "carol"))
        assertNull(again)
    }

    // Mirror of the private SenderKeys.ensureOwned logic, exercised against the fake store.
    private suspend fun ensureOwned(
        store: FakeSenderKeyStore,
        groupId: String,
        members: List<String>,
    ): List<String>? {
        val covered = store.coveredMembers(groupId)
        val missing = members.filter { it !in covered }
        if (missing.isEmpty()) return null
        store.markCovered(groupId, missing)
        return missing
    }
}
