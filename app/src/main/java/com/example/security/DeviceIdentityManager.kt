package com.example.security

import android.graphics.Bitmap
import android.graphics.Color
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import com.google.zxing.BarcodeFormat
import com.google.zxing.EncodeHintType
import com.google.zxing.qrcode.QRCodeWriter
import java.nio.ByteBuffer
import java.security.KeyFactory
import java.security.KeyPair
import java.security.KeyPairGenerator
import java.security.KeyStore
import java.security.MessageDigest
import java.security.PrivateKey
import java.security.PublicKey
import java.security.SecureRandom
import java.security.Signature
import java.security.spec.ECGenParameterSpec
import java.security.spec.X509EncodedKeySpec
import javax.crypto.Cipher
import javax.crypto.KeyAgreement
import javax.crypto.Mac
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

data class LocalDeviceIdentity(
    val deviceId: String,
    val publicKeyBase64: String,
    val fingerprintShort: String
)

data class QrPairingPayload(
    val deviceId: String,
    val deviceName: String,
    val host: String,
    val port: Int,
    val publicKeyBase64: String,
    val nonce: String
) {
    fun encode(): String = listOf(
        "LINK1",
        deviceId,
        Base64.encodeToString(deviceName.toByteArray(Charsets.UTF_8), Base64.NO_WRAP),
        host,
        port.toString(),
        publicKeyBase64,
        nonce
    ).joinToString("|")

    companion object {
        fun decode(raw: String): QrPairingPayload? {
            val parts = raw.trim().split("|")
            if (parts.size != 7 || parts[0] != "LINK1") return null
            val port = parts[4].toIntOrNull() ?: return null
            val decodedName = runCatching {
                String(Base64.decode(parts[2], Base64.NO_WRAP), Charsets.UTF_8)
            }.getOrNull() ?: return null
            return QrPairingPayload(
                deviceId = parts[1],
                deviceName = decodedName,
                host = parts[3],
                port = port,
                publicKeyBase64 = parts[5],
                nonce = parts[6]
            )
        }
    }
}

class EncryptedSessionCipher(
    private val aesKey: SecretKey,
    private val hmacKey: SecretKey
) {
    private val random = SecureRandom()
    private var outgoingSeq = 1L
    private var highestIncomingSeq = 0L

    @Synchronized
    fun encryptFrame(plaintext: ByteArray): ByteArray {
        val seq = outgoingSeq++
        val iv = ByteArray(12).also { random.nextBytes(it) }
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, aesKey, GCMParameterSpec(128, iv))
        val aad = ByteBuffer.allocate(8).putLong(seq).array()
        cipher.updateAAD(aad)
        val ciphertext = cipher.doFinal(plaintext)
        return ByteBuffer.allocate(8 + 12 + ciphertext.size)
            .putLong(seq)
            .put(iv)
            .put(ciphertext)
            .array()
    }

    @Synchronized
    fun decryptFrame(frame: ByteArray): ByteArray {
        require(frame.size > 20) { "Encrypted frame too short" }
        val buffer = ByteBuffer.wrap(frame)
        val seq = buffer.long
        if (seq <= highestIncomingSeq) {
            throw SecurityException("Replay attack detected: seq=$seq <= $highestIncomingSeq")
        }
        val iv = ByteArray(12)
        buffer.get(iv)
        val ciphertext = ByteArray(buffer.remaining())
        buffer.get(ciphertext)

        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.DECRYPT_MODE, aesKey, GCMParameterSpec(128, iv))
        val aad = ByteBuffer.allocate(8).putLong(seq).array()
        cipher.updateAAD(aad)
        val plaintext = cipher.doFinal(ciphertext)
        highestIncomingSeq = seq
        return plaintext
    }

    fun computeMac(data: ByteArray): ByteArray {
        val mac = Mac.getInstance("HmacSHA256")
        mac.init(hmacKey)
        return mac.doFinal(data)
    }
}

class DeviceIdentityManager(
    private val useSoftwareFallbackWhenKeystoreUnavailable: Boolean = true
) {
    private var cachedSoftwareKeyPair: KeyPair? = null
    private val secureRandom = SecureRandom()

    fun getOrCreateIdentity(): LocalDeviceIdentity {
        val keyPair = loadOrGenerateIdentityKeyPair()
        val pubEncoded = keyPair.public.encoded
        val sha256 = MessageDigest.getInstance("SHA-256").digest(pubEncoded)
        val hex = sha256.joinToString("") { "%02x".format(it) }
        val deviceId = "lnk_${hex.take(24)}"
        val fingerprintShort = hex.take(16).chunked(4).joinToString("-").uppercase()
        val pubBase64 = Base64.encodeToString(pubEncoded, Base64.NO_WRAP)
        return LocalDeviceIdentity(
            deviceId = deviceId,
            publicKeyBase64 = pubBase64,
            fingerprintShort = fingerprintShort
        )
    }

    fun signChallenge(challenge: ByteArray): ByteArray {
        val privateKey = getPrivateKey()
        val signature = Signature.getInstance("SHA256withECDSA")
        signature.initSign(privateKey)
        signature.update(challenge)
        return signature.sign()
    }

    fun verifyPeerSignature(publicKeyBase64: String, challenge: ByteArray, signatureBytes: ByteArray): Boolean {
        return runCatching {
            val pubBytes = Base64.decode(publicKeyBase64, Base64.NO_WRAP)
            val keySpec = X509EncodedKeySpec(pubBytes)
            val pubKey = KeyFactory.getInstance("EC").generatePublic(keySpec)
            val signature = Signature.getInstance("SHA256withECDSA")
            signature.initVerify(pubKey)
            signature.update(challenge)
            signature.verify(signatureBytes)
        }.getOrDefault(false)
    }

    fun deriveDeviceIdFromPublicKey(publicKeyBase64: String): String {
        val pubBytes = Base64.decode(publicKeyBase64, Base64.NO_WRAP)
        val sha256 = MessageDigest.getInstance("SHA-256").digest(pubBytes)
        val hex = sha256.joinToString("") { "%02x".format(it) }
        return "lnk_${hex.take(24)}"
    }

    fun generateEphemeralEcdhKeyPair(): KeyPair {
        val kpg = KeyPairGenerator.getInstance("EC")
        kpg.initialize(ECGenParameterSpec("secp256r1"))
        return kpg.generateKeyPair()
    }

    fun deriveSessionCipher(
        localEphemeralPrivate: PrivateKey,
        remoteEphemeralPublicBase64: String,
        transcriptBytes: ByteArray
    ): EncryptedSessionCipher {
        val remotePubBytes = Base64.decode(remoteEphemeralPublicBase64, Base64.NO_WRAP)
        val remotePub = KeyFactory.getInstance("EC").generatePublic(X509EncodedKeySpec(remotePubBytes))
        val ka = KeyAgreement.getInstance("ECDH")
        ka.init(localEphemeralPrivate)
        ka.doPhase(remotePub, true)
        val sharedSecret = ka.generateSecret()

        val digest = MessageDigest.getInstance("SHA-256")
        digest.update(sharedSecret)
        digest.update(transcriptBytes)
        val keyMaterial = digest.digest()

        val aesKey = SecretKeySpec(keyMaterial, 0, 16, "AES")
        val hmacDigest = MessageDigest.getInstance("SHA-256").digest(keyMaterial + "LINK_HMAC".toByteArray())
        val hmacKey = SecretKeySpec(hmacDigest, "HmacSHA256")
        return EncryptedSessionCipher(aesKey, hmacKey)
    }

    fun computeShortAuthCode(
        localDeviceId: String,
        remoteDeviceId: String,
        localEphemeralPubBase64: String,
        remoteEphemeralPubBase64: String
    ): String {
        val orderedIds = listOf(localDeviceId, remoteDeviceId).sorted()
        val orderedPubs = listOf(localEphemeralPubBase64, remoteEphemeralPubBase64).sorted()
        val transcript = (orderedIds + orderedPubs).joinToString("|").toByteArray(Charsets.UTF_8)
        val hash = MessageDigest.getInstance("SHA-256").digest(transcript)
        val codeInt = ByteBuffer.wrap(hash, 0, 4).int and 0x7FFFFFFF
        return "%06d".format(codeInt % 1_000_000)
    }

    fun randomNonce(byteCount: Int = 16): String {
        val bytes = ByteArray(byteCount)
        secureRandom.nextBytes(bytes)
        return Base64.encodeToString(bytes, Base64.NO_WRAP)
    }

    fun deriveRelaySecretFromSession(
        localEphemeralPrivate: PrivateKey,
        remoteEphemeralPublicBase64: String,
        orderedDeviceIds: List<String>
    ): String {
        val remotePubBytes = Base64.decode(remoteEphemeralPublicBase64, Base64.NO_WRAP)
        val remotePub = KeyFactory.getInstance("EC").generatePublic(X509EncodedKeySpec(remotePubBytes))
        val ka = KeyAgreement.getInstance("ECDH")
        ka.init(localEphemeralPrivate)
        ka.doPhase(remotePub, true)
        val shared = ka.generateSecret()
        val digest = MessageDigest.getInstance("SHA-256")
        digest.update(shared)
        digest.update("LINK_CLOUD_RELAY_V1|${orderedDeviceIds.sorted().joinToString("|")}".toByteArray(Charsets.UTF_8))
        return Base64.encodeToString(digest.digest(), 0, 16, Base64.NO_WRAP)
    }

    fun encryptRelayPayload(relaySecretBase64: String, plaintext: String, aadText: String): String {
        val keyBytes = Base64.decode(relaySecretBase64, Base64.NO_WRAP)
        val key = SecretKeySpec(keyBytes, 0, 16, "AES")
        val iv = ByteArray(12).also { secureRandom.nextBytes(it) }
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, key, GCMParameterSpec(128, iv))
        cipher.updateAAD(aadText.toByteArray(Charsets.UTF_8))
        val ciphertext = cipher.doFinal(plaintext.toByteArray(Charsets.UTF_8))
        val combined = ByteBuffer.allocate(12 + ciphertext.size).put(iv).put(ciphertext).array()
        return Base64.encodeToString(combined, Base64.NO_WRAP)
    }

    fun decryptRelayPayload(relaySecretBase64: String, encodedEnvelope: String, aadText: String): String {
        val combined = Base64.decode(encodedEnvelope, Base64.NO_WRAP)
        require(combined.size > 16) { "Invalid encrypted relay envelope" }
        val buf = ByteBuffer.wrap(combined)
        val iv = ByteArray(12).also { buf.get(it) }
        val ciphertext = ByteArray(buf.remaining()).also { buf.get(it) }
        val keyBytes = Base64.decode(relaySecretBase64, Base64.NO_WRAP)
        val key = SecretKeySpec(keyBytes, 0, 16, "AES")
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.DECRYPT_MODE, key, GCMParameterSpec(128, iv))
        cipher.updateAAD(aadText.toByteArray(Charsets.UTF_8))
        return String(cipher.doFinal(ciphertext), Charsets.UTF_8)
    }

    fun generateQrBitmap(content: String, sizePx: Int = 512): Bitmap {
        val hints = mapOf(EncodeHintType.MARGIN to 1)
        val matrix = QRCodeWriter().encode(content, BarcodeFormat.QR_CODE, sizePx, sizePx, hints)
        val bitmap = Bitmap.createBitmap(sizePx, sizePx, Bitmap.Config.ARGB_8888)
        for (x in 0 until sizePx) {
            for (y in 0 until sizePx) {
                bitmap.setPixel(x, y, if (matrix[x, y]) Color.BLACK else Color.WHITE)
            }
        }
        return bitmap
    }

    private fun getPrivateKey(): PrivateKey = loadOrGenerateIdentityKeyPair().private

    private fun loadOrGenerateIdentityKeyPair(): KeyPair {
        val keystoreResult = runCatching {
            val ks = KeyStore.getInstance(ANDROID_KEYSTORE).apply { load(null) }
            val existingEntry = ks.getEntry(KEY_ALIAS, null) as? KeyStore.PrivateKeyEntry
            if (existingEntry != null) {
                KeyPair(existingEntry.certificate.publicKey, existingEntry.privateKey)
            } else {
                val kpg = KeyPairGenerator.getInstance(KeyProperties.KEY_ALGORITHM_EC, ANDROID_KEYSTORE)
                val spec = KeyGenParameterSpec.Builder(
                    KEY_ALIAS,
                    KeyProperties.PURPOSE_SIGN or KeyProperties.PURPOSE_VERIFY
                )
                    .setAlgorithmParameterSpec(ECGenParameterSpec("secp256r1"))
                    .setDigests(KeyProperties.DIGEST_SHA256)
                    .build()
                kpg.initialize(spec)
                kpg.generateKeyPair()
            }
        }

        if (keystoreResult.isSuccess) {
            return keystoreResult.getOrThrow()
        }

        if (!useSoftwareFallbackWhenKeystoreUnavailable) {
            throw keystoreResult.exceptionOrNull()!!
        }

        return cachedSoftwareKeyPair ?: synchronized(this) {
            cachedSoftwareKeyPair ?: run {
                val kpg = KeyPairGenerator.getInstance("EC")
                kpg.initialize(ECGenParameterSpec("secp256r1"))
                kpg.generateKeyPair().also { cachedSoftwareKeyPair = it }
            }
        }
    }

    companion object {
        private const val ANDROID_KEYSTORE = "AndroidKeyStore"
        private const val KEY_ALIAS = "link_device_identity_v1"
    }
}
