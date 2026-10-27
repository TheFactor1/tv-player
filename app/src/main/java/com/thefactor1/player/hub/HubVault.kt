package com.thefactor1.player.hub

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import androidx.core.content.edit
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * The paired hub's address and token, encrypted with an AES key that lives in the Android Keystore
 * and never leaves it. The prefs file holds only ciphertext, so a copied file is useless off this box.
 */
class HubVault(context: Context) {
    private val prefs = context.getSharedPreferences("hub_vault", Context.MODE_PRIVATE)

    /** "http://192.168.50.10:8097", or empty before pairing. */
    var address: String
        get() = read(KEY_ADDRESS)
        set(value) = write(KEY_ADDRESS, value)

    /** The hub's bearer token, or empty before pairing (or after the hub refused it). */
    var token: String
        get() = read(KEY_TOKEN)
        set(value) = write(KEY_TOKEN, value)

    private fun read(name: String): String {
        val stored = prefs.getString(name, null) ?: return ""
        return try {
            val bytes = Base64.decode(stored, Base64.NO_WRAP)
            val cipher = Cipher.getInstance(TRANSFORMATION)
            cipher.init(Cipher.DECRYPT_MODE, key(), GCMParameterSpec(TAG_BITS, bytes, 0, IV_BYTES))
            String(cipher.doFinal(bytes, IV_BYTES, bytes.size - IV_BYTES), Charsets.UTF_8)
        } catch (e: Exception) {
            // A key lost with a factory reset or a restore onto another box: pair again rather than crash.
            ""
        }
    }

    private fun write(name: String, value: String) {
        if (value.isEmpty()) {
            prefs.edit { remove(name) }
            return
        }
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.ENCRYPT_MODE, key())
        val sealed = cipher.iv + cipher.doFinal(value.toByteArray(Charsets.UTF_8))
        prefs.edit { putString(name, Base64.encodeToString(sealed, Base64.NO_WRAP)) }
    }

    private fun key(): SecretKey {
        val store = KeyStore.getInstance(KEYSTORE).apply { load(null) }
        (store.getEntry(ALIAS, null) as? KeyStore.SecretKeyEntry)?.let { return it.secretKey }
        return KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, KEYSTORE).apply {
            init(
                KeyGenParameterSpec.Builder(ALIAS, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                    .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                    .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                    .setKeySize(256)
                    .build(),
            )
        }.generateKey()
    }

    private companion object {
        const val KEYSTORE = "AndroidKeyStore"
        const val ALIAS = "hub-vault"
        const val TRANSFORMATION = "AES/GCM/NoPadding"
        const val IV_BYTES = 12
        const val TAG_BITS = 128
        const val KEY_ADDRESS = "address"
        const val KEY_TOKEN = "token"
    }
}
