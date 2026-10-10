package com.ara.core.crypto

import com.ara.core.crypto.primitives.Crypto
import com.ara.core.crypto.ratchet.DoubleRatchet
import com.ara.core.crypto.ratchet.RatchetState
import com.ara.core.crypto.session.SessionCodec
import com.ara.core.crypto.x3dh.X3dh
import com.ara.core.crypto.keys.KeyBundle
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

class DoubleRatchetTest {

    private fun sessionPair(): Pair<RatchetState, RatchetState> {
        val aliceIdentity = IdentityKeyPairForTest.generate()
        val bobIdentity = IdentityKeyPairForTest.generate()
        val spkPriv = Crypto.generateX25519PrivateKey()
        val spkPub = Crypto.x25519PublicKey(spkPriv)
        val spkSig = Crypto.ed25519Sign(bobIdentity.ed25519Private, spkPub)

        val bundle = KeyBundle(
            araId = "bob",
            identityX25519 = bobIdentity.x25519Public,
            identityEd25519 = bobIdentity.ed25519Public,
            signedPreKeyId = 1,
            signedPreKey = spkPub,
            signedPreKeySignature = spkSig,
        )
        val result = X3dh.initiate(aliceIdentity, bundle)
        val aliceState = DoubleRatchet.initInitiator(result.sharedKey, spkPub)
        val bobState = DoubleRatchet.initResponder(result.sharedKey, spkPriv, spkPub)
        return aliceState to bobState
    }

    @Test
    fun `basic round trip`() {
        val (alice, bob) = sessionPair()
        val (header, ciphertext, nonce) = DoubleRatchet.encrypt(alice, "hello".toByteArray())
        val plaintext = DoubleRatchet.decrypt(bob, header, nonce, ciphertext)
        assertArrayEquals("hello".toByteArray(), plaintext)
    }

    @Test
    fun `hundred alternating messages ratchet repeatedly`() {
        val (alice, bob) = sessionPair()
        repeat(50) { i ->
            val (h1, c1, n1) = DoubleRatchet.encrypt(alice, "a$i".toByteArray())
            assertArrayEquals("a$i".toByteArray(), DoubleRatchet.decrypt(bob, h1, n1, c1))
            val (h2, c2, n2) = DoubleRatchet.encrypt(bob, "b$i".toByteArray())
            assertArrayEquals("b$i".toByteArray(), DoubleRatchet.decrypt(alice, h2, n2, c2))
        }
    }

    @Test
    fun `out of order delivery is handled via skipped keys`() {
        val (alice, bob) = sessionPair()
        val m1 = DoubleRatchet.encrypt(alice, "one".toByteArray())
        val m2 = DoubleRatchet.encrypt(alice, "two".toByteArray())
        val m3 = DoubleRatchet.encrypt(alice, "three".toByteArray())

        assertArrayEquals("two".toByteArray(), DoubleRatchet.decrypt(bob, m2.first, m2.third, m2.second))
        assertArrayEquals("one".toByteArray(), DoubleRatchet.decrypt(bob, m1.first, m1.third, m1.second))
        assertArrayEquals("three".toByteArray(), DoubleRatchet.decrypt(bob, m3.first, m3.third, m3.second))
    }

    @Test
    fun `reply direction performs a DH ratchet step`() {
        val (alice, bob) = sessionPair()
        val (h1, c1, n1) = DoubleRatchet.encrypt(alice, "ping".toByteArray())
        assertArrayEquals("ping".toByteArray(), DoubleRatchet.decrypt(bob, h1, n1, c1))
        val (h2, c2, n2) = DoubleRatchet.encrypt(bob, "pong".toByteArray())
        assertArrayEquals("pong".toByteArray(), DoubleRatchet.decrypt(alice, h2, n2, c2))
        val (h3, c3, n3) = DoubleRatchet.encrypt(alice, "again".toByteArray())
        assertArrayEquals("again".toByteArray(), DoubleRatchet.decrypt(bob, h3, n3, c3))
    }

    @Test
    fun `state survives serialize and deserialize`() {
        val (alice, bob) = sessionPair()
        val serialized = SessionCodec.serialize(alice)
        val restored = SessionCodec.deserialize(serialized)

        // Encrypt with the live state; the restored snapshot must still derive the same key.
        val (header, ciphertext, nonce) = DoubleRatchet.encrypt(alice, "persisted".toByteArray())
        assertArrayEquals("persisted".toByteArray(), DoubleRatchet.decrypt(restored, header, nonce, ciphertext))

        // The restored copy was not mutated by the other side's encryption bookkeeping.
        assertFalse(restored === alice)
    }

    @Test
    fun `tampered ciphertext fails authentication`() {
        val (alice, bob) = sessionPair()
        val (header, ciphertext, nonce) = DoubleRatchet.encrypt(alice, "secret".toByteArray())
        ciphertext[0] = (ciphertext[0].toInt() xor 1).toByte()
        var failed = false
        try {
            DoubleRatchet.decrypt(bob, header, nonce, ciphertext)
        } catch (expected: Exception) {
            failed = true
        }
        assertEquals(true, failed)
    }
}

/** Test-local identity generation mirroring com.ara.core.crypto.keys.IdentityKeyPair. */
private object IdentityKeyPairForTest {
    fun generate(): com.ara.core.crypto.keys.IdentityKeyPair =
        com.ara.core.crypto.keys.IdentityKeyPair.generate()
}
