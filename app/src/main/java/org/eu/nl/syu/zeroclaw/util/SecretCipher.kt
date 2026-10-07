/*
 * Copyright 2026 ZeroClaw Community
 *
 * Licensed under the MIT License. See LICENSE in the project root.
 */

package org.eu.nl.syu.zeroclaw.util

import android.util.Log
import java.io.File
import java.security.SecureRandom
import javax.crypto.Cipher
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.SecretKeySpec

/**
 * Encrypts secrets for the engine config using the engine's own scheme.
 *
 * The engine stores secrets as `enc2:<hex(nonce ‖ ciphertext ‖ tag)>`, a
 * ChaCha20-Poly1305 AEAD blob, with a 32-byte master key hex-encoded in
 * `<config-dir>/.secret_key`. The engine creates that key with an atomic
 * `hard_link` publish, which Android forbids in the app data dir, so key
 * creation fails and every config save errors with "Failed to encrypt".
 *
 * This helper creates the key file itself (the engine only *reads* an existing
 * key) and produces values in the exact format the engine decrypts, so secrets
 * are encrypted at rest and engine-side saves succeed.
 */
object SecretCipher {
    private const val TAG = "SecretCipher"
    private const val KEY_FILE_NAME = ".secret_key"
    private const val KEY_LEN = 32
    private const val NONCE_LEN = 12
    private const val BYTE_HEX_WIDTH = 2
    private const val HEX_RADIX = 16

    /**
     * Encrypts [plaintext] for storage in the engine config.
     *
     * Values that are empty or already encrypted/resolved (`enc2:`, legacy
     * `enc:`, `op://`) are returned unchanged. On failure the plaintext is
     * returned so config generation can continue; the failure is logged.
     *
     * @param plaintext Value to encrypt.
     * @param configDir Engine config directory holding `.secret_key`.
     * @return `enc2:`-prefixed ciphertext, or the original value.
     */
    @Suppress("TooGenericExceptionCaught")
    fun encrypt(
        plaintext: String,
        configDir: File,
    ): String {
        if (plaintext.isEmpty() || isAlreadyProtected(plaintext)) return plaintext
        return try {
            val key = loadOrCreateKey(configDir)
            val nonce = ByteArray(NONCE_LEN).also { SecureRandom().nextBytes(it) }
            val cipher = Cipher.getInstance("ChaCha20-Poly1305")
            cipher.init(
                Cipher.ENCRYPT_MODE,
                SecretKeySpec(key, "ChaCha20"),
                IvParameterSpec(nonce),
            )
            val ciphertext = cipher.doFinal(plaintext.toByteArray(Charsets.UTF_8))
            "enc2:" + hex(nonce + ciphertext)
        } catch (e: Exception) {
            Log.w(TAG, "Secret encryption failed; writing value unencrypted: ${e.message}")
            plaintext
        }
    }

    /** Whether a value is already encrypted or an external reference. */
    private fun isAlreadyProtected(value: String): Boolean = value.startsWith("enc2:") || value.startsWith("enc:") || value.startsWith("op://")

    /**
     * Reads the 256-bit master key, creating it if absent.
     *
     * @param configDir Engine config directory.
     * @return The 32-byte key.
     */
    internal fun loadOrCreateKey(configDir: File): ByteArray {
        val keyFile = File(configDir, KEY_FILE_NAME)
        if (keyFile.exists()) {
            val hexKey = keyFile.readText().trim()
            require(hexKey.length == KEY_LEN * BYTE_HEX_WIDTH) {
                "Secret key file must contain ${KEY_LEN * BYTE_HEX_WIDTH} hex characters"
            }
            return ByteArray(KEY_LEN) { index ->
                val start = index * BYTE_HEX_WIDTH
                hexKey.substring(start, start + BYTE_HEX_WIDTH).toInt(HEX_RADIX).toByte()
            }
        }
        val key = ByteArray(KEY_LEN).also { SecureRandom().nextBytes(it) }
        configDir.mkdirs()
        keyFile.writeText(hex(key))
        // Restrict to the app UID (owner-only read/write), matching the engine's 0600.
        keyFile.setReadable(false, false)
        keyFile.setWritable(false, false)
        keyFile.setReadable(true, true)
        keyFile.setWritable(true, true)
        return key
    }

    /** Lowercase hex encoding. */
    private fun hex(bytes: ByteArray): String = bytes.joinToString(separator = "") { "%02x".format(it) }
}
