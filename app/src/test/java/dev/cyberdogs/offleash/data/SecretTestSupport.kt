package dev.cyberdogs.offleash.data

import java.security.SecureRandom
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey

/**
 * The secret-box test support, kept when the account test
 * machinery was deleted in 0.9.0 (owner order, 2026-11-04): the
 * generic sealed-box utility survives in the tree (dormant — it
 * has no production caller in the account-free build), and its
 * tests still need a locally generated AES key and the box under
 * test. These helpers used to live in FakeAccountTransport.kt,
 * which existed to drive the deleted session managers.
 */

/** A locally generated AES key + the box under test, for JVM tests. */
fun testSecretKey(): SecretKey =
    KeyGenerator.getInstance("AES").apply { init(256, SecureRandom()) }.generateKey()

fun testSecretBox(key: SecretKey = testSecretKey()): AesGcmSecretBox =
    AesGcmSecretBox(keyProvider = { key })
