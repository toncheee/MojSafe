package com.example.safeimport

import org.bouncycastle.crypto.generators.Argon2BytesGenerator
import org.bouncycastle.crypto.params.Argon2Parameters
import java.io.ByteArrayOutputStream
import java.security.SecureRandom
import javax.crypto.Cipher
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

/**
 * A standalone, portable encrypted-database file format for MojSafe backups.
 *
 * This is deliberately independent from the app's internal (Android-Keystore-backed)
 * storage: the resulting file can be copied anywhere — local disk, a cloud drive, email —
 * and later restored on any device, protected only by the passphrase you choose when
 * creating it (there's no dependency on this specific phone's hardware keystore).
 *
 * Key derivation: Argon2id (the current recommended password-hashing/KDF algorithm,
 * winner of the Password Hashing Competition, memory-hard against GPU/ASIC cracking)
 * with a 4096 KiB memory cost, 3 iterations, deriving a 256-bit key.
 * Encryption: AES-256-GCM (authenticated encryption — a wrong passphrase or any
 * tampering/corruption fails decryption loudly instead of returning garbage).
 *
 * File layout: "MSFE1" magic (5 bytes) | salt (16 bytes) | iv (12 bytes) | ciphertext+tag.
 */
object SecureBackup {
    private const val MAGIC = "MSFE1"
    private const val SALT_LEN = 16
    private const val IV_LEN = 12
    private const val KEY_LEN_BYTES = 32 // AES-256
    private const val ARGON2_MEMORY_KB = 4096
    private const val ARGON2_ITERATIONS = 3
    private const val ARGON2_PARALLELISM = 1

    class WrongPassphraseOrCorruptFile(cause: Throwable) : Exception(
        "Wrong passphrase, or the file isn't a valid MojSafe backup.", cause
    )

    fun encrypt(plaintext: ByteArray, passphrase: CharArray): ByteArray {
        val salt = ByteArray(SALT_LEN).also { SecureRandom().nextBytes(it) }
        val key = deriveKey(passphrase, salt)
        val iv = ByteArray(IV_LEN).also { SecureRandom().nextBytes(it) }

        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, SecretKeySpec(key, "AES"), GCMParameterSpec(128, iv))
        val ciphertext = cipher.doFinal(plaintext)

        val out = ByteArrayOutputStream()
        out.write(MAGIC.toByteArray(Charsets.US_ASCII))
        out.write(salt)
        out.write(iv)
        out.write(ciphertext)
        return out.toByteArray()
    }

    fun decrypt(data: ByteArray, passphrase: CharArray): ByteArray {
        val magicBytes = MAGIC.toByteArray(Charsets.US_ASCII)
        require(data.size > magicBytes.size + SALT_LEN + IV_LEN) {
            "File is too short to be a MojSafe backup."
        }
        val magic = data.copyOfRange(0, magicBytes.size)
        require(magic.contentEquals(magicBytes)) { "Not a MojSafe encrypted backup file." }

        var offset = magicBytes.size
        val salt = data.copyOfRange(offset, offset + SALT_LEN); offset += SALT_LEN
        val iv = data.copyOfRange(offset, offset + IV_LEN); offset += IV_LEN
        val ciphertext = data.copyOfRange(offset, data.size)

        val key = deriveKey(passphrase, salt)
        return try {
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(Cipher.DECRYPT_MODE, SecretKeySpec(key, "AES"), GCMParameterSpec(128, iv))
            cipher.doFinal(ciphertext)
        } catch (e: Exception) {
            throw WrongPassphraseOrCorruptFile(e)
        }
    }

    private fun deriveKey(passphrase: CharArray, salt: ByteArray): ByteArray {
        val params = Argon2Parameters.Builder(Argon2Parameters.ARGON2_id)
            .withVersion(Argon2Parameters.ARGON2_VERSION_13)
            .withMemoryAsKB(ARGON2_MEMORY_KB)
            .withIterations(ARGON2_ITERATIONS)
            .withParallelism(ARGON2_PARALLELISM)
            .withSalt(salt)
            .build()
        val generator = Argon2BytesGenerator()
        generator.init(params)
        val out = ByteArray(KEY_LEN_BYTES)
        val passphraseBytes = String(passphrase).toByteArray(Charsets.UTF_8)
        generator.generateBytes(passphraseBytes, out, 0, out.size)
        return out
    }
}
