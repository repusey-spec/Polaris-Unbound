package app.hush.music.polaris

import android.content.Context
import java.security.MessageDigest

/** Local user intent survives an offline unlike and a stale remote snapshot. */
class PolarisLikeIntents(context: Context) {
    private val prefs = context.applicationContext.getSharedPreferences("polaris_like_intents14", Context.MODE_PRIVATE)
    private fun key(account: String, id: String) = MessageDigest.getInstance("SHA-256")
        .digest((account + "\u0000" + id).toByteArray()).joinToString("") { "%02x".format(it) }
    @Synchronized fun record(account: String, id: String, liked: Boolean) {
        prefs.edit().putBoolean(key(account, id), liked).apply()
    }
    @Synchronized fun desired(account: String, id: String): Boolean? {
        val key = key(account, id)
        return if (prefs.contains(key)) prefs.getBoolean(key, false) else null
    }
}
