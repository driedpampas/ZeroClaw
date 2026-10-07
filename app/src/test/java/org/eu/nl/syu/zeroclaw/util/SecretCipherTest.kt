/*
 * Copyright 2026 ZeroClaw Community
 *
 * Licensed under the MIT License. See LICENSE in the project root.
 */

package org.eu.nl.syu.zeroclaw.util

import java.io.File
import org.junit.jupiter.api.Assertions.assertArrayEquals
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir

@Suppress("MagicNumber")
class SecretCipherTest {
    @TempDir
    lateinit var tempDir: File

    @Test
    fun `encrypt produces an enc2 blob of the expected length`() {
        val plaintext = "sk-plaintext-secret"

        val encrypted = SecretCipher.encrypt(plaintext, tempDir)

        assertTrue(encrypted.startsWith("enc2:"), "must use the engine's enc2: format")
        val hex = encrypted.removePrefix("enc2:")
        assertEquals(0, hex.length % 2, "hex payload must be even length")
        // nonce(12) + ciphertext(len) + Poly1305 tag(16)
        assertEquals((12 + plaintext.length + 16) * 2, hex.length)
        assertTrue(hex.all { it in "0123456789abcdef" }, "payload must be lowercase hex")
    }

    @Test
    fun `encrypt uses a fresh nonce per call`() {
        val a = SecretCipher.encrypt("same-value", tempDir)
        val b = SecretCipher.encrypt("same-value", tempDir)
        assertNotEquals(a, b, "random nonce must vary the ciphertext")
    }

    @Test
    fun `key file is created once and reused`() {
        val keyFile = File(tempDir, ".secret_key")
        val first = SecretCipher.loadOrCreateKey(tempDir)
        assertTrue(keyFile.exists(), "master key file must be created")
        assertEquals(64, keyFile.readText().trim().length, "32 bytes hex-encoded")

        val second = SecretCipher.loadOrCreateKey(tempDir)
        assertArrayEquals(first, second, "existing key must be reused")
    }

    @Test
    fun `blank and already protected values pass through`() {
        assertEquals("", SecretCipher.encrypt("", tempDir))
        assertEquals("enc2:aabb", SecretCipher.encrypt("enc2:aabb", tempDir))
        assertEquals("enc:old", SecretCipher.encrypt("enc:old", tempDir))
        assertEquals("op://vault/item/field", SecretCipher.encrypt("op://vault/item/field", tempDir))
    }
}
