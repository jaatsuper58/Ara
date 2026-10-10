package com.ara.core.crypto

import com.ara.core.crypto.keys.IdentityKeyPair
import com.ara.core.crypto.keys.KeyBundle
import com.ara.core.crypto.primitives.Crypto
import com.ara.core.crypto.x3dh.X3dh
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class X3dhTest {

    private fun bundleOf(responder: IdentityKeyPair, spk: SignedPreKeyStub, opk: OneTimeStub?): KeyBundle =
        KeyBundle(
            araId = "peer",
            identityX25519 = responder.x25519Public,
            identityEd25519 = responder.ed25519Public,
            signedPreKeyId = spk.id,
            signedPreKey = spk.publicKey,
            signedPreKeySignature = spk.signature,
            oneTimePreKeyId = opk?.id,
            oneTimePreKey = opk?.publicKey,
        )

    private data class SignedPreKeyStub(val id: Int, val priv: ByteArray, val publicKey: ByteArray, val signature: ByteArray)
    private data class OneTimeStub(val id: Int, val priv: ByteArray, val publicKey: ByteArray)

    private fun makeSpk(identity: IdentityKeyPair): SignedPreKeyStub {
        val priv = Crypto.generateX25519PrivateKey()
        val pub = Crypto.x25519PublicKey(priv)
        return SignedPreKeyStub(7, priv, pub, Crypto.ed25519Sign(identity.ed25519Private, pub))
    }

    private fun makeOpk(): OneTimeStub {
        val priv = Crypto.generateX25519PrivateKey()
        return OneTimeStub(11, priv, Crypto.x25519PublicKey(priv))
    }

    @Test
    fun `initiator and responder derive the same shared key`() {
        val alice = IdentityKeyPair.generate()
        val bob = IdentityKeyPair.generate()
        val spk = makeSpk(bob)
        val opk = makeOpk()

        val result = X3dh.initiate(alice, bundleOf(bob, spk, opk))
        val bobKey = X3dh.respond(
            ourIdentity = bob,
            ourSignedPreKeyPrivate = spk.priv,
            ourOneTimePreKeyPrivate = opk.priv,
            theirIdentityX25519 = alice.x25519Public,
            theirEphemeralPublic = result.ephemeralPublic,
        )

        assertArrayEquals(result.sharedKey, bobKey)
        assertEquals(32, result.sharedKey.size)
    }

    @Test
    fun `works without a one-time prekey`() {
        val alice = IdentityKeyPair.generate()
        val bob = IdentityKeyPair.generate()
        val spk = makeSpk(bob)

        val result = X3dh.initiate(alice, bundleOf(bob, spk, null))
        val bobKey = X3dh.respond(bob, spk.priv, null, alice.x25519Public, result.ephemeralPublic)

        assertArrayEquals(result.sharedKey, bobKey)
    }

    @Test
    fun `tampered signed prekey signature is rejected`() {
        val alice = IdentityKeyPair.generate()
        val bob = IdentityKeyPair.generate()
        val spk = makeSpk(bob)
        val bundle = bundleOf(bob, spk, null).copy(
            signedPreKeySignature = ByteArray(64),
        )

        assertThrows(IllegalArgumentException::class.java) { X3dh.initiate(alice, bundle) }
    }
}
