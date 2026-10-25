/**
 * Wavlin Project (C) 2026
 * Licensed under GPL-3.0 | See git history for contributors
 */

package com.wavlin.music.spotify.account

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import androidx.core.content.edit
import kotlinx.serialization.KSerializer
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json
import timber.log.Timber
import java.io.File
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * Persists the Spotify session. The cookies and the access token are encrypted with a key held in
 * the Android Keystore. The library lists are cached as JSON files so the Spotify tab keeps working offline.
 */
class SpotifyStore(
    context: Context,
) {
    private val appContext = context.applicationContext
    private val prefs = appContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    private val cacheDir = File(appContext.filesDir, CACHE_DIR_NAME)
    private val json = Json { ignoreUnknownKeys = true }

    var spDc: String
        get() = readSecret(KEY_SP_DC)
        set(value) = writeSecret(KEY_SP_DC, value)

    var spKey: String
        get() = readSecret(KEY_SP_KEY)
        set(value) = writeSecret(KEY_SP_KEY, value)

    var accessToken: String
        get() = readSecret(KEY_ACCESS_TOKEN)
        set(value) = writeSecret(KEY_ACCESS_TOKEN, value)

    var tokenExpiresAtMs: Long
        get() = prefs.getLong(KEY_TOKEN_EXPIRES_AT, 0L)
        set(value) = prefs.edit { putLong(KEY_TOKEN_EXPIRES_AT, value) }

    var accountName: String
        get() = prefs.getString(KEY_ACCOUNT_NAME, "").orEmpty()
        set(value) = prefs.edit { putString(KEY_ACCOUNT_NAME, value) }

    var accountAvatarUrl: String?
        get() = prefs.getString(KEY_ACCOUNT_AVATAR_URL, null)?.takeIf { it.isNotBlank() }
        set(value) = prefs.edit { putString(KEY_ACCOUNT_AVATAR_URL, value) }

    val isConnected: Boolean
        get() = spDc.isNotBlank()

    fun isTokenExpiringSoon(bufferMs: Long = 300_000L): Boolean =
        accessToken.isBlank() || System.currentTimeMillis() >= tokenExpiresAtMs - bufferMs

    fun clearAll() {
        prefs.edit { clear() }
        cacheDir.deleteRecursively()
    }

    // ── Offline cache ────────────────────────────────────────────────────────

    fun readPlaylists(): List<SpotifyPlaylist>? = readCache("playlists.json", ListSerializer(SpotifyPlaylist.serializer()))

    fun writePlaylists(playlists: List<SpotifyPlaylist>) = writeCache("playlists.json", ListSerializer(SpotifyPlaylist.serializer()), playlists)

    fun readLikedTracks(): List<SpotifyWebTrack>? = readCache("liked.json", ListSerializer(SpotifyWebTrack.serializer()))

    fun writeLikedTracks(tracks: List<SpotifyWebTrack>) = writeCache("liked.json", ListSerializer(SpotifyWebTrack.serializer()), tracks)

    fun readPlaylistTracks(playlistId: String): List<SpotifyWebTrack>? =
        readCache("playlist_${playlistId.fileSafe()}.json", ListSerializer(SpotifyWebTrack.serializer()))

    fun writePlaylistTracks(
        playlistId: String,
        tracks: List<SpotifyWebTrack>,
    ) = writeCache("playlist_${playlistId.fileSafe()}.json", ListSerializer(SpotifyWebTrack.serializer()), tracks)

    private fun String.fileSafe(): String = filter { it.isLetterOrDigit() || it == '_' || it == '-' }

    private fun <T> readCache(
        name: String,
        serializer: KSerializer<T>,
    ): T? =
        try {
            val file = File(cacheDir, name)
            if (file.exists()) json.decodeFromString(serializer, file.readText()) else null
        } catch (e: Exception) {
            Timber.w(e, "Spotify cache read failed: $name")
            null
        }

    private fun <T> writeCache(
        name: String,
        serializer: KSerializer<T>,
        value: T,
    ) {
        try {
            cacheDir.mkdirs()
            val temp = File(cacheDir, "$name.tmp")
            temp.writeText(json.encodeToString(serializer, value))
            if (!temp.renameTo(File(cacheDir, name))) temp.delete()
        } catch (e: Exception) {
            Timber.w(e, "Spotify cache write failed: $name")
        }
    }

    // ── Encryption ───────────────────────────────────────────────────────────

    private fun readSecret(key: String): String {
        val stored = prefs.getString(key, null) ?: return ""
        return try {
            decrypt(stored)
        } catch (e: Exception) {
            // The keystore key is gone (restored backup, cleared lock screen): the session is unusable
            Timber.w(e, "Spotify secret could not be decrypted: $key")
            ""
        }
    }

    private fun writeSecret(
        key: String,
        value: String,
    ) {
        if (value.isEmpty()) {
            prefs.edit { remove(key) }
            return
        }
        try {
            prefs.edit { putString(key, encrypt(value)) }
        } catch (e: Exception) {
            Timber.e(e, "Spotify secret could not be encrypted: $key")
        }
    }

    private fun secretKey(): SecretKey {
        val keyStore = KeyStore.getInstance(ANDROID_KEYSTORE).apply { load(null) }
        (keyStore.getKey(KEY_ALIAS, null) as? SecretKey)?.let { return it }
        val spec =
            KeyGenParameterSpec
                .Builder(KEY_ALIAS, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256)
                .build()
        return KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, ANDROID_KEYSTORE).apply { init(spec) }.generateKey()
    }

    private fun encrypt(plain: String): String {
        val cipher = Cipher.getInstance(TRANSFORMATION).apply { init(Cipher.ENCRYPT_MODE, secretKey()) }
        val encrypted = cipher.doFinal(plain.toByteArray(Charsets.UTF_8))
        return Base64.encodeToString(cipher.iv + encrypted, Base64.NO_WRAP)
    }

    private fun decrypt(stored: String): String {
        val combined = Base64.decode(stored, Base64.NO_WRAP)
        require(combined.size > GCM_IV_SIZE) { "Encrypted data too short" }
        val cipher =
            Cipher.getInstance(TRANSFORMATION).apply {
                init(Cipher.DECRYPT_MODE, secretKey(), GCMParameterSpec(GCM_TAG_BITS, combined, 0, GCM_IV_SIZE))
            }
        return String(cipher.doFinal(combined, GCM_IV_SIZE, combined.size - GCM_IV_SIZE), Charsets.UTF_8)
    }

    private companion object {
        const val PREFS_NAME = "spotify_account"
        const val CACHE_DIR_NAME = "spotify_cache"
        const val KEY_SP_DC = "sp_dc"
        const val KEY_SP_KEY = "sp_key"
        const val KEY_ACCESS_TOKEN = "access_token"
        const val KEY_TOKEN_EXPIRES_AT = "access_token_expires_at"
        const val KEY_ACCOUNT_NAME = "account_name"
        const val KEY_ACCOUNT_AVATAR_URL = "account_avatar_url"
        const val ANDROID_KEYSTORE = "AndroidKeyStore"
        const val KEY_ALIAS = "wavlin_spotify_account_key"
        const val TRANSFORMATION = "AES/GCM/NoPadding"
        const val GCM_IV_SIZE = 12
        const val GCM_TAG_BITS = 128
    }
}
