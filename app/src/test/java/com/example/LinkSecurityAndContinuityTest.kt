package com.example

import com.example.connectivity.WireFrameType
import com.example.security.DeviceIdentityManager
import com.example.security.QrPairingPayload
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File
import java.security.GeneralSecurityException
import java.security.MessageDigest
import android.util.Base64

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class LinkSecurityAndContinuityTest {

    @Test
    fun `identity manager creates deterministic device id and signs challenges`() {
        val manager = DeviceIdentityManager(useSoftwareFallbackWhenKeystoreUnavailable = true)
        val identity = manager.getOrCreateIdentity()
        assertTrue(identity.deviceId.startsWith("lnk_"))
        assertEquals(identity.deviceId, manager.deriveDeviceIdFromPublicKey(identity.publicKeyBase64))

        val challenge = "handshake_nonce_12345".toByteArray()
        val sig = manager.signChallenge(challenge)
        assertTrue(manager.verifyPeerSignature(identity.publicKeyBase64, challenge, sig))
        assertFalse(manager.verifyPeerSignature(identity.publicKeyBase64, "tampered".toByteArray(), sig))
    }

    @Test
    fun `ecdh aes-gcm session encrypts decrypts and rejects replayed frames`() {
        val alice = DeviceIdentityManager(useSoftwareFallbackWhenKeystoreUnavailable = true)
        val bob = DeviceIdentityManager(useSoftwareFallbackWhenKeystoreUnavailable = true)

        val aliceEph = alice.generateEphemeralEcdhKeyPair()
        val bobEph = bob.generateEphemeralEcdhKeyPair()
        val alicePubB64 = Base64.encodeToString(aliceEph.public.encoded, Base64.NO_WRAP)
        val bobPubB64 = Base64.encodeToString(bobEph.public.encoded, Base64.NO_WRAP)
        val transcript = "LINK_TEST_TRANSCRIPT".toByteArray()

        val aliceCipher = alice.deriveSessionCipher(aliceEph.private, bobPubB64, transcript)
        val bobCipher = bob.deriveSessionCipher(bobEph.private, alicePubB64, transcript)

        val plaintext = "Encrypted file chunk payload".toByteArray()
        val frame1 = aliceCipher.encryptFrame(plaintext)
        val decrypted1 = bobCipher.decryptFrame(frame1)
        assertArrayEquals(plaintext, decrypted1)

        try {
            bobCipher.decryptFrame(frame1)
            fail("Expected SecurityException on replayed frame")
        } catch (expected: SecurityException) {
            assertTrue(expected.message?.contains("Replay") == true)
        }
    }

    @Test
    fun `e2ee relay secret derives symmetrically and rejects tampered aad or wrong key`() {
        val alice = DeviceIdentityManager(useSoftwareFallbackWhenKeystoreUnavailable = true)
        val bob = DeviceIdentityManager(useSoftwareFallbackWhenKeystoreUnavailable = true)

        val aliceEph = alice.generateEphemeralEcdhKeyPair()
        val bobEph = bob.generateEphemeralEcdhKeyPair()
        val alicePubB64 = Base64.encodeToString(aliceEph.public.encoded, Base64.NO_WRAP)
        val bobPubB64 = Base64.encodeToString(bobEph.public.encoded, Base64.NO_WRAP)

        val aliceRelaySecret = alice.deriveRelaySecretFromSession(
            aliceEph.private,
            bobPubB64,
            listOf("lnk_bob", "lnk_alice")
        )
        val bobRelaySecret = bob.deriveRelaySecretFromSession(
            bobEph.private,
            alicePubB64,
            listOf("lnk_alice", "lnk_bob")
        )
        assertEquals(aliceRelaySecret, bobRelaySecret)

        val aad = "room_lnk_alice_lnk_bob|lnk_alice|lnk_bob"
        val envelope = alice.encryptRelayPayload(aliceRelaySecret, "{\"type\":\"MEDIA_CMD\"}", aad)
        val decrypted = bob.decryptRelayPayload(bobRelaySecret, envelope, aad)
        assertEquals("{\"type\":\"MEDIA_CMD\"}", decrypted)

        try {
            bob.decryptRelayPayload(bobRelaySecret, envelope, "tampered_room|lnk_mallory|lnk_bob")
            fail("Expected AEAD failure on modified AAD")
        } catch (_: GeneralSecurityException) {
            // Expected
        }

        val wrongKey = Base64.encodeToString(ByteArray(16) { 0x5A }, Base64.NO_WRAP)
        try {
            bob.decryptRelayPayload(wrongKey, envelope, aad)
            fail("Expected AEAD failure with wrong key")
        } catch (_: GeneralSecurityException) {
            // Expected
        }
    }

    @Test
    fun `wire frame types include h264 stream frame roundtrip`() {
        assertEquals(WireFrameType.SCREEN_H264_FRAME, WireFrameType.fromCode(WireFrameType.SCREEN_H264_FRAME.code))
        assertEquals(WireFrameType.JSON_CONTROL, WireFrameType.fromCode(1))
    }

    @Test
    fun `short auth code is symmetric regardless of party order`() {
        val manager = DeviceIdentityManager(useSoftwareFallbackWhenKeystoreUnavailable = true)
        val codeA = manager.computeShortAuthCode("lnk_aaa", "lnk_bbb", "pubA", "pubB")
        val codeB = manager.computeShortAuthCode("lnk_bbb", "lnk_aaa", "pubB", "pubA")
        assertEquals(6, codeA.length)
        assertEquals(codeA, codeB)
    }

    @Test
    fun `qr pairing payload encodes and decodes cleanly`() {
        val payload = QrPairingPayload(
            deviceId = "lnk_1234567890abcdef12345678",
            deviceName = "Pixel 9 Pro | Studio",
            host = "192.168.1.42",
            port = 51721,
            publicKeyBase64 = "MFkwEwYHKoZIzj0CAQYIKoZIzj0DAQcDQgAE",
            nonce = "nonce123"
        )
        // DeviceName with spaces/punctuation Base64-roundtrips cleanly even though pipe is delimiter
        val encoded = payload.encode()
        val decoded = QrPairingPayload.decode(encoded)
        assertNotNull(decoded)
        assertEquals(payload, decoded)
    }

    @Test
    fun `sha256 file digest matches streaming bytes`() {
        val tmp = File.createTempFile("link_sha_test", ".bin")
        try {
            val bytes = ByteArray(150_000) { (it % 251).toByte() }
            tmp.writeBytes(bytes)
            val expected = MessageDigest.getInstance("SHA-256").digest(bytes)
                .joinToString("") { "%02x".format(it) }
            val actual = MessageDigest.getInstance("SHA-256").apply {
                tmp.inputStream().use { ins ->
                    val buf = ByteArray(65_536)
                    while (true) {
                        val read = ins.read(buf)
                        if (read <= 0) break
                        update(buf, 0, read)
                    }
                }
            }.digest().joinToString("") { "%02x".format(it) }
            assertEquals(expected, actual)
        } finally {
            tmp.delete()
        }
    }
}
