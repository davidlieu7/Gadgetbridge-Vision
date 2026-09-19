/*  Copyright (C) 2026 Arjan Schrijver, José Rebelo, Dany Mestas

    This file is part of Gadgetbridge.

    Gadgetbridge is free software: you can redistribute it and/or modify
    it under the terms of the GNU Affero General Public License as published
    by the Free Software Foundation, either version 3 of the License, or
    (at your option) any later version.

    Gadgetbridge is distributed in the hope that it will be useful,
    but WITHOUT ANY WARRANTY; without even the implied warranty of
    MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
    GNU Affero General Public License for more details.

    You should have received a copy of the GNU Affero General Public License
    along with this program.  If not, see <https://www.gnu.org/licenses/>. */
package nodomain.freeyourgadget.gadgetbridge.externalservices

import android.content.Context
import android.content.SharedPreferences
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import androidx.annotation.VisibleForTesting
import androidx.core.content.edit
import org.slf4j.LoggerFactory
import java.security.GeneralSecurityException
import java.security.KeyStore
import java.security.ProviderException
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * Credentials of one external service, in a preference file of its own that only that service
 * reads, encrypted at rest.
 *
 * Each value is encrypted with AES-256-GCM under a key that lives in the Android Keystore and never
 * leaves it, and is stored as base64 of the 12-byte IV followed by the ciphertext and its 16-byte
 * tag. The associated data is `<fileName>/<key>`, so a value copied to another entry or another
 * service's file fails to decrypt. Entry names are stored in the clear.
 *
 * A file whose contents no longer decrypt is cleared rather than left to throw on every read: the
 * key it was written under is gone, so nothing in it can be recovered, and the user is asked to log
 * in again instead of meeting a service that is permanently broken.
 */
abstract class ExternalServiceTokenStore(context: Context, private val fileName: String) {
    private val appContext: Context = context.applicationContext

    private val key: SecretKey by lazy { secretKey() }

    private val storage: SharedPreferences by lazy {
        dropLegacyFile()
        appContext.getSharedPreferences(fileName + FILE_SUFFIX, Context.MODE_PRIVATE)
    }

    protected val preferences = Values()

    fun clearTokens() {
        storage.edit { clear() }
    }

    /** The key of this store, created in the Android Keystore on first use. */
    @VisibleForTesting
    protected open fun secretKey(): SecretKey {
        val keyStore = KeyStore.getInstance(ANDROID_KEYSTORE).apply { load(null) }
        val alias = KEY_ALIAS_PREFIX + fileName
        (keyStore.getKey(alias, null) as? SecretKey)?.let { return it }
        val generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, ANDROID_KEYSTORE)
        generator.init(
            KeyGenParameterSpec.Builder(alias, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256)
                .build()
        )
        return generator.generateKey()
    }

    /**
     * Removes the master key androidx.security's EncryptedSharedPreferences kept in the Keystore.
     * It is shared by every file that library wrote, and nothing reads those files.
     */
    @VisibleForTesting
    protected open fun deleteLegacyMasterKey() {
        val keyStore = KeyStore.getInstance(ANDROID_KEYSTORE).apply { load(null) }
        keyStore.deleteEntry(LEGACY_MASTER_KEY_ALIAS)
    }

    /**
     * Empties the file EncryptedSharedPreferences used under [fileName]. Its contents cannot be read
     * without that library, so the user logs in again.
     */
    private fun dropLegacyFile() {
        val legacy = appContext.getSharedPreferences(fileName, Context.MODE_PRIVATE)
        if (legacy.all.isEmpty()) {
            return
        }
        LOG.info("Dropping the {} credentials stored by EncryptedSharedPreferences", fileName)
        legacy.edit(commit = true) { clear() }
        try {
            deleteLegacyMasterKey()
        } catch (e: Exception) {
            if (!e.isCryptoFailure()) throw e
            LOG.warn("Unable to delete the EncryptedSharedPreferences master key", e)
        }
    }

    /**
     * The Keystore reports failures as checked [GeneralSecurityException]s and, on some devices, as
     * [ProviderException]; a corrupt stored value fails base64 decoding with an
     * [IllegalArgumentException].
     */
    private fun Exception.isCryptoFailure(): Boolean =
        this is GeneralSecurityException || this is ProviderException || this is IllegalArgumentException

    private fun associatedData(name: String): ByteArray = "$fileName/$name".toByteArray(Charsets.UTF_8)

    private fun encrypt(name: String, value: String): String {
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.ENCRYPT_MODE, key)
        cipher.updateAAD(associatedData(name))
        val iv = cipher.iv
        val sealed = cipher.doFinal(value.toByteArray(Charsets.UTF_8))
        return Base64.encodeToString(iv + sealed, Base64.NO_WRAP)
    }

    private fun decrypt(name: String, stored: String): String {
        val bytes = Base64.decode(stored, Base64.NO_WRAP)
        require(bytes.size > IV_LENGTH) { "Stored value too short" }
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.DECRYPT_MODE, key, GCMParameterSpec(TAG_LENGTH_BITS, bytes, 0, IV_LENGTH))
        cipher.updateAAD(associatedData(name))
        return String(cipher.doFinal(bytes, IV_LENGTH, bytes.size - IV_LENGTH), Charsets.UTF_8)
    }

    private fun read(name: String): String? {
        val stored = storage.getString(name, null) ?: return null
        return try {
            decrypt(name, stored)
        } catch (e: Exception) {
            if (!e.isCryptoFailure()) throw e
            LOG.warn("Unable to decrypt {} token preferences, resetting them instead", fileName, e)
            storage.edit(commit = true) { clear() }
            null
        }
    }

    /** Typed access to the decrypted values of this store. */
    protected inner class Values {
        fun getString(name: String, defValue: String?): String? = read(name) ?: defValue

        fun getInt(name: String, defValue: Int): Int = read(name)?.toIntOrNull() ?: defValue

        /**
         * Encrypts and writes every value [block] puts, and drops every entry it removes, in one
         * edit. When the Keystore refuses to encrypt, nothing is written and the store keeps its
         * previous contents.
         */
        fun edit(block: Editor.() -> Unit) {
            val editor = Editor().apply(block)
            val encrypted = try {
                editor.values.mapValues { (name, value) -> encrypt(name, value) }
            } catch (e: Exception) {
                if (!e.isCryptoFailure()) throw e
                LOG.error("Unable to encrypt {} token preferences", fileName, e)
                return
            }
            storage.edit {
                editor.removed.forEach { remove(it) }
                encrypted.forEach { (name, value) -> putString(name, value) }
            }
        }
    }

    protected class Editor {
        internal val values = LinkedHashMap<String, String>()
        internal val removed = LinkedHashSet<String>()

        fun putString(name: String, value: String) {
            removed.remove(name)
            values[name] = value
        }

        fun putInt(name: String, value: Int) {
            putString(name, value.toString())
        }

        fun remove(name: String) {
            values.remove(name)
            removed.add(name)
        }
    }

    companion object {
        private val LOG = LoggerFactory.getLogger(ExternalServiceTokenStore::class.java)

        private const val ANDROID_KEYSTORE = "AndroidKeyStore"
        private const val TRANSFORMATION = "AES/GCM/NoPadding"
        private const val IV_LENGTH = 12
        private const val TAG_LENGTH_BITS = 128
        private const val KEY_ALIAS_PREFIX = "external_service_"
        private const val FILE_SUFFIX = "_keystore"
        private const val LEGACY_MASTER_KEY_ALIAS = "_androidx_security_master_key_"
    }
}

/**
 * Credentials of a service the user authenticates to with a single token they paste in, which
 * neither expires on its own nor can be refreshed.
 */
open class ApiTokenStore(context: Context, fileName: String) : ExternalServiceTokenStore(context, fileName) {
    fun saveToken(apiToken: String) {
        preferences.edit { putString(KEY_API_TOKEN, apiToken) }
    }

    fun getApiToken(): String? = preferences.getString(KEY_API_TOKEN, null)

    open fun isLoggedIn(): Boolean = !getApiToken().isNullOrBlank()

    companion object {
        private const val KEY_API_TOKEN = "api_token"
    }
}
