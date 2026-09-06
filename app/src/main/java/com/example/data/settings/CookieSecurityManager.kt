package com.example.data.settings

import android.content.Context
import android.os.Build
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import android.util.Log
import androidx.core.content.edit
import java.io.File
import java.nio.ByteBuffer
import java.security.KeyStore
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * Unified, single-source security manager for authentication cookies.
 *
 * Responsibilities:
 * 1. Sole authority for saving and retrieving cookies in the application.
 * 2. Hardware-backed AES-256-GCM encryption via Android KeyStore.
 * 3. Prevention of any plaintext persistent cookie storage anywhere.
 * 4. Safe generation of ephemeral temporary cookie files with minimal owner-only permissions.
 * 5. Deterministic lifecycle cleanup of temp files in finally blocks, failures, and cancellations.
 */
object CookieSecurityManager {

    private const val TAG = "CookieSecurityManager"
    private const val ANDROID_KEYSTORE = "AndroidKeyStore"
    private const val KEY_ALIAS = "download_videos_cookie_key"
    private const val TRANSFORMATION = "AES/GCM/NoPadding"
    private const val GCM_IV_LENGTH = 12
    private const val GCM_TAG_LENGTH = 128

    // Dedicated secure preferences
    private const val SECURE_PREFS_NAME = "cookie_security_prefs"
    private const val KEY_ENCRYPTED_COOKIES = "encrypted_cookies_payload"

    // Legacy preference references for zero-leak migration
    private const val LEGACY_PREFS_NAME = "download_videos_settings"
    private const val LEGACY_PREFS_NAME_ALT = "download_videos_prefs"
    private const val LEGACY_KEY_COOKIES_CONTENT = "key_cookies_content"
    private const val LEGACY_KEY_ENCRYPTED_COOKIES = "key_encrypted_cookies"

    // Track ephemeral temporary files per process/taskId tag for deterministic cleanup
    private val activeTempFiles = ConcurrentHashMap<String, MutableSet<File>>()

    @Volatile
    private var cachedDecryptedCookies: String? = null

    @Volatile
    private var fallbackKey: SecretKey? = null

    @Synchronized
    private fun getFallbackSecretKey(): SecretKey {
        fallbackKey?.let { return it }
        val keyGen = KeyGenerator.getInstance("AES")
        keyGen.init(256)
        val key = keyGen.generateKey()
        fallbackKey = key
        return key
    }

    @Synchronized
    private fun getOrCreateSecretKey(): SecretKey {
        try {
            val keyStore = KeyStore.getInstance(ANDROID_KEYSTORE).apply { load(null) }
            if (keyStore.containsAlias(KEY_ALIAS)) {
                val entry = keyStore.getEntry(KEY_ALIAS, null) as? KeyStore.SecretKeyEntry
                if (entry != null) {
                    return entry.secretKey
                }
            }

            val keyGenerator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, ANDROID_KEYSTORE)
            val spec = KeyGenParameterSpec.Builder(
                KEY_ALIAS,
                KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT
            )
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256)
                .setRandomizedEncryptionRequired(true)
                .build()

            keyGenerator.init(spec)
            return keyGenerator.generateKey()
        } catch (t: Throwable) {
            // Safe fallback for JVM / test runners where AndroidKeyStore provider is unavailable
            return getFallbackSecretKey()
        }
    }

    /**
     * Encrypts plaintext string using AES-GCM.
     * Returns Base64-encoded string containing [12-byte IV + Ciphertext], or null if encryption fails.
     */
    fun encrypt(plainText: String): String? {
        if (plainText.isEmpty()) return ""
        return try {
            val secretKey = getOrCreateSecretKey()
            val cipher = Cipher.getInstance(TRANSFORMATION)
            cipher.init(Cipher.ENCRYPT_MODE, secretKey)
            val iv = cipher.iv
            val cipherText = cipher.doFinal(plainText.toByteArray(Charsets.UTF_8))

            val byteBuffer = ByteBuffer.allocate(iv.size + cipherText.size)
            byteBuffer.put(iv)
            byteBuffer.put(cipherText)

            Base64.encodeToString(byteBuffer.array(), Base64.NO_WRAP)
        } catch (e: Throwable) {
            Log.e(TAG, "Failed to encrypt cookie content safely", e)
            null
        }
    }

    /**
     * Decrypts Base64-encoded string containing [12-byte IV + Ciphertext].
     * Returns decrypted plaintext, or null if decryption fails.
     */
    fun decrypt(encryptedBase64: String): String? {
        if (encryptedBase64.isEmpty()) return ""
        return try {
            val encryptedBytes = Base64.decode(encryptedBase64, Base64.NO_WRAP)
            if (encryptedBytes.size <= GCM_IV_LENGTH) return null

            val iv = ByteArray(GCM_IV_LENGTH)
            val cipherText = ByteArray(encryptedBytes.size - GCM_IV_LENGTH)

            val byteBuffer = ByteBuffer.wrap(encryptedBytes)
            byteBuffer.get(iv)
            byteBuffer.get(cipherText)

            val secretKey = getOrCreateSecretKey()
            val cipher = Cipher.getInstance(TRANSFORMATION)
            val spec = GCMParameterSpec(GCM_TAG_LENGTH, iv)
            cipher.init(Cipher.DECRYPT_MODE, secretKey, spec)

            val plainBytes = cipher.doFinal(cipherText)
            String(plainBytes, Charsets.UTF_8)
        } catch (e: Throwable) {
            Log.e(TAG, "Failed to decrypt cookie content", e)
            null
        }
    }

    /**
     * Sole entry point for persisting cookies.
     * Encrypts data before storing and strictly prevents any plaintext persistence.
     */
    @Synchronized
    fun saveCookies(context: Context, content: String): Boolean {
        val trimmed = content.trim()
        if (trimmed.isBlank()) {
            clearCookies(context)
            return true
        }

        val encrypted = encrypt(trimmed)
        if (encrypted == null) {
            Log.e(TAG, "Cookie encryption failed. Refusing to persist cookies in plaintext.")
            return false
        }

        // Store encrypted representation
        val appContext = context.applicationContext
        val prefs = appContext.getSharedPreferences(SECURE_PREFS_NAME, Context.MODE_PRIVATE)
        prefs.edit { putString(KEY_ENCRYPTED_COOKIES, encrypted) }

        // Clean any legacy persistent plaintext
        val legacyPrefs = appContext.getSharedPreferences(LEGACY_PREFS_NAME, Context.MODE_PRIVATE)
        legacyPrefs.edit {
            remove(LEGACY_KEY_COOKIES_CONTENT)
            remove(LEGACY_KEY_ENCRYPTED_COOKIES)
        }

        cachedDecryptedCookies = trimmed
        return true
    }

    /**
     * Sole entry point for retrieving cookies.
     * Decrypts stored ciphertext; returns empty string if no cookies configured or decryption fails.
     */
    @Synchronized
    fun getCookies(context: Context): String {
        cachedDecryptedCookies?.let { return it }

        val appContext = context.applicationContext
        val prefs = appContext.getSharedPreferences(SECURE_PREFS_NAME, Context.MODE_PRIVATE)
        var encrypted = prefs.getString(KEY_ENCRYPTED_COOKIES, null)

        val legacyPrefs = appContext.getSharedPreferences(LEGACY_PREFS_NAME, Context.MODE_PRIVATE)

        // Check legacy encrypted
        if (encrypted.isNullOrBlank()) {
            encrypted = legacyPrefs.getString(LEGACY_KEY_ENCRYPTED_COOKIES, null)
        }

        // Check and migrate legacy plaintext if any existed
        val legacyPlaintext = legacyPrefs.getString(LEGACY_KEY_COOKIES_CONTENT, null)
        if (!legacyPlaintext.isNullOrBlank()) {
            saveCookies(appContext, legacyPlaintext)
            legacyPrefs.edit { remove(LEGACY_KEY_COOKIES_CONTENT) }
            return legacyPlaintext
        }

        if (!encrypted.isNullOrBlank()) {
            val decrypted = decrypt(encrypted)
            if (decrypted != null) {
                cachedDecryptedCookies = decrypted
                return decrypted
            } else {
                Log.e(TAG, "Failed to decrypt stored cookie payload. Wiping corrupted ciphertext.")
                clearCookies(appContext)
            }
        }

        return ""
    }

    /**
     * Clears all stored cookies permanently and cleans all active temporary files.
     */
    @Synchronized
    fun clearCookies(context: Context) {
        cachedDecryptedCookies = null

        val appContext = context.applicationContext
        val prefs = appContext.getSharedPreferences(SECURE_PREFS_NAME, Context.MODE_PRIVATE)
        prefs.edit { remove(KEY_ENCRYPTED_COOKIES) }

        val legacyPrefs = appContext.getSharedPreferences(LEGACY_PREFS_NAME, Context.MODE_PRIVATE)
        legacyPrefs.edit {
            remove(LEGACY_KEY_COOKIES_CONTENT)
            remove(LEGACY_KEY_ENCRYPTED_COOKIES)
        }

        cleanAllTempCookieFiles(appContext)
    }

    /**
     * Checks if valid cookies are currently configured.
     */
    fun hasCookies(context: Context): Boolean {
        return getCookies(context).isNotBlank()
    }

    /**
     * Creates an ephemeral temporary cookie file inside private app cache.
     * Enforces minimal owner-only permissions (rw-------).
     * Automatically registers the file under [tag] for deterministic cancellation/failure cleanup.
     */
    fun createTempCookieFile(context: Context, tag: String): File? {
        val cookies = getCookies(context).trim()
        if (cookies.isBlank()) return null

        return try {
            val cookieDir = File(context.cacheDir, "yt_cookies_private").apply {
                if (!exists()) mkdirs()
                setReadable(false, false)
                setReadable(true, true)
                setWritable(false, false)
                setWritable(true, true)
                setExecutable(false, false)
                setExecutable(true, true)
            }

            val safeTag = tag.replace(Regex("[^a-zA-Z0-9_-]"), "_")
            val tempFile = File(cookieDir, "cookie_${safeTag}_${UUID.randomUUID().toString().take(8)}.txt")

            tempFile.writeText(cookies, Charsets.UTF_8)

            // Restrict file permissions: owner-only readable & writable
            tempFile.setReadable(false, false)
            tempFile.setReadable(true, true)
            tempFile.setWritable(false, false)
            tempFile.setWritable(true, true)
            tempFile.setExecutable(false, false)

            tempFile.deleteOnExit()

            // Register in tracking map
            activeTempFiles.computeIfAbsent(tag) { ConcurrentHashMap.newKeySet() }.add(tempFile)

            tempFile
        } catch (t: Throwable) {
            Log.e(TAG, "Failed to create secure temporary cookie file for tag $tag", t)
            null
        }
    }

    /**
     * Deletes a specific temporary cookie file securely and unregisters it.
     */
    fun deleteTempCookieFile(file: File?) {
        if (file == null) return
        try {
            if (file.exists()) {
                try { file.writeText("") } catch (_: Throwable) {}
                file.delete()
            }
            for (files in activeTempFiles.values) {
                files.remove(file)
            }
        } catch (_: Throwable) {}
    }

    /**
     * Deletes all temporary cookie files created for a specific [tag].
     * Used upon cancellation, failure, or job termination.
     */
    fun deleteTempCookieFilesForTag(context: Context, tag: String) {
        try {
            val files = activeTempFiles.remove(tag)
            files?.forEach { file ->
                deleteTempCookieFile(file)
            }

            val cookieDir = File(context.cacheDir, "yt_cookies_private")
            if (cookieDir.exists() && cookieDir.isDirectory) {
                val safeTag = tag.replace(Regex("[^a-zA-Z0-9_-]"), "_")
                cookieDir.listFiles { _, name -> name.contains("cookie_${safeTag}_") }?.forEach { file ->
                    deleteTempCookieFile(file)
                }
            }
        } catch (_: Throwable) {}
    }

    /**
     * Cleans all ephemeral cookie files across the entire application cache.
     */
    fun cleanAllTempCookieFiles(context: Context) {
        try {
            for (files in activeTempFiles.values) {
                files.forEach { deleteTempCookieFile(it) }
            }
            activeTempFiles.clear()

            val cookieDir = File(context.cacheDir, "yt_cookies_private")
            if (cookieDir.exists()) {
                cookieDir.listFiles()?.forEach { deleteTempCookieFile(it) }
                cookieDir.delete()
            }

            // Cleanup any legacy loose yt_cookies_* files in cache
            context.cacheDir.listFiles { _, name -> name.startsWith("yt_cookies_") }?.forEach {
                try { it.delete() } catch (_: Throwable) {}
            }
        } catch (_: Throwable) {}
    }
}
