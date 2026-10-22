package app.hush.music.lite

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import app.hush.music.innertube.PlaybackAuthState
import org.json.JSONObject
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * Private, Android Keystore AES-GCM storage for the minimally required
 * YouTube Music session state. Never export the stored cookies, log them,
 * back them up, or put them in a GitHub workflow.
 *
 * A cryptographic/keystore failure is fail-closed: ask for login again.
 */
internal object LiteSessionStore {
    private const val PREFERENCES = "hush_lite_private_session"
    private const val KEY_PAYLOAD = "session_ciphertext_v1"
    private const val KEY_ALIAS = "hush_lite_session_aes_1"
    private const val AAD = "HUSH_LITE_SESSION_1"
    private const val IV_SIZE = 12
    private const val TAG_BITS = 128

    private fun secretKey(): SecretKey {
        val keystore = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (keystore.getKey(KEY_ALIAS, null) as? SecretKey)?.let { return it }
        return KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore").apply {
            init(
                KeyGenParameterSpec.Builder(
                    KEY_ALIAS,
                    KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT
                ).setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                    .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                    .setRandomizedEncryptionRequired(true)
                    .setKeySize(256)
                    .build()
            )
        }.generateKey()
    }

    private fun associatedData(context: Context): ByteArray =
        (AAD + ":" + context.packageName).toByteArray(Charsets.UTF_8)

    fun save(context: Context, state: PlaybackAuthState): Boolean {
        if (!state.hasPlaybackLoginContext) return false
        return runCatching {
            val json = JSONObject()
                .put("cookie", state.cookie.orEmpty())
                .put("dataSyncId", state.dataSyncId.orEmpty())
                .put("visitorData", state.visitorData.orEmpty())
                .toString()
                .toByteArray(Charsets.UTF_8)
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(Cipher.ENCRYPT_MODE, secretKey())
            cipher.updateAAD(associatedData(context))
            val iv = cipher.iv
            require(iv.size == IV_SIZE)
            val ciphertext = cipher.doFinal(json)
            val encoded = Base64.encodeToString(iv + ciphertext, Base64.NO_WRAP)
            context.getSharedPreferences(PREFERENCES, Context.MODE_PRIVATE)
                .edit().putString(KEY_PAYLOAD, encoded).commit()
        }.getOrDefault(false)
    }

    fun restore(context: Context): PlaybackAuthState? =
        runCatching {
            val encoded = context.getSharedPreferences(PREFERENCES, Context.MODE_PRIVATE)
                .getString(KEY_PAYLOAD, null) ?: return@runCatching null
            val data = Base64.decode(encoded, Base64.NO_WRAP)
            require(data.size > IV_SIZE + 16)
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(
                Cipher.DECRYPT_MODE, secretKey(),
                GCMParameterSpec(TAG_BITS, data.copyOfRange(0, IV_SIZE))
            )
            cipher.updateAAD(associatedData(context))
            val json = JSONObject(
                String(cipher.doFinal(data.copyOfRange(IV_SIZE, data.size)), Charsets.UTF_8)
            )
            PlaybackAuthState(
                cookie = json.optString("cookie"),
                dataSyncId = json.optString("dataSyncId"),
                visitorData = json.optString("visitorData"),
            ).normalized().takeIf { it.hasPlaybackLoginContext }
        }.getOrNull()

    fun clear(context: Context) {
        context.getSharedPreferences(PREFERENCES, Context.MODE_PRIVATE)
            .edit().remove(KEY_PAYLOAD).apply()
    }
}
