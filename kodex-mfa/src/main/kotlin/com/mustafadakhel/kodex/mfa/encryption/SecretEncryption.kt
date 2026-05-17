package com.mustafadakhel.kodex.mfa.encryption

import org.bouncycastle.jce.provider.BouncyCastleProvider
import java.security.SecureRandom
import java.security.Security
import java.util.Base64
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

public data class EncryptedSecret(
    val ciphertext: String,
    val nonce: String
)

public interface SecretEncryption {
    public fun encrypt(plaintext: String): EncryptedSecret
    public fun decrypt(encrypted: EncryptedSecret): String
}

public class AesGcmSecretEncryption(
    private val masterKey: SecretKey
) : SecretEncryption {

    private val secureRandom = SecureRandom()

    init {
        if (Security.getProvider(BouncyCastleProvider.PROVIDER_NAME) == null) {
            Security.addProvider(BouncyCastleProvider())
        }
    }

    public companion object {
        private const val GCM_IV_LENGTH = 12
        private const val GCM_TAG_LENGTH = 128
        private const val ALGORITHM = "AES"
        private const val TRANSFORMATION = "AES/GCM/NoPadding"

        public fun generateKey(): SecretKey {
            val keyGen = KeyGenerator.getInstance(ALGORITHM)
            keyGen.init(256, SecureRandom())
            return keyGen.generateKey()
        }

        public fun fromBase64Key(base64Key: String): SecretKey {
            val keyBytes = Base64.getDecoder().decode(base64Key)
            require(keyBytes.size == 32) { "Master key must be 256 bits (32 bytes)" }
            return SecretKeySpec(keyBytes, ALGORITHM)
        }

        public fun fromHexKey(hexKey: String): SecretKey {
            require(hexKey.length == 64) { "Hex key must be 64 characters (256 bits)" }
            val keyBytes = hexKey.chunked(2).map { it.toInt(16).toByte() }.toByteArray()
            return SecretKeySpec(keyBytes, ALGORITHM)
        }
    }

    override fun encrypt(plaintext: String): EncryptedSecret {
        val nonce = ByteArray(GCM_IV_LENGTH)
        secureRandom.nextBytes(nonce)

        val cipher = Cipher.getInstance(TRANSFORMATION)
        val gcmSpec = GCMParameterSpec(GCM_TAG_LENGTH, nonce)
        cipher.init(Cipher.ENCRYPT_MODE, masterKey, gcmSpec)

        val plaintextBytes = plaintext.toByteArray(Charsets.UTF_8)
        val ciphertextBytes = cipher.doFinal(plaintextBytes)

        return EncryptedSecret(
            ciphertext = Base64.getEncoder().encodeToString(ciphertextBytes),
            nonce = Base64.getEncoder().encodeToString(nonce)
        )
    }

    override fun decrypt(encrypted: EncryptedSecret): String {
        val ciphertextBytes = Base64.getDecoder().decode(encrypted.ciphertext)
        val nonceBytes = Base64.getDecoder().decode(encrypted.nonce)

        val cipher = Cipher.getInstance(TRANSFORMATION)
        val gcmSpec = GCMParameterSpec(GCM_TAG_LENGTH, nonceBytes)
        cipher.init(Cipher.DECRYPT_MODE, masterKey, gcmSpec)

        val plaintextBytes = cipher.doFinal(ciphertextBytes)
        return String(plaintextBytes, Charsets.UTF_8)
    }
}
