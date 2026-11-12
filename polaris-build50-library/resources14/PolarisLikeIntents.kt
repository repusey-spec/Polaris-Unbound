package app.hush.music.polaris

import android.content.Context
import android.util.Base64
import java.security.MessageDigest

/** Pending local intent survives offline sync; acknowledged intent expires after convergence. */
class PolarisLikeIntents(context: Context, private val now: () -> Long = System::currentTimeMillis) {
    private val prefs = context.applicationContext.getSharedPreferences("polaris_like_intents14", Context.MODE_PRIVATE)
    private fun prefix(account: String) = MessageDigest.getInstance("SHA-256")
        .digest(account.toByteArray()).joinToString("") { "%02x".format(it) } + ":"
    private fun key(account: String, id: String) = prefix(account) + Base64.encodeToString(id.toByteArray(), Base64.URL_SAFE or Base64.NO_WRAP)
    @Synchronized fun record(account: String, id: String, liked: Boolean) {
        val k = key(account, id)
        prefs.edit().putBoolean(k, liked).putLong(k + ":at", now()).putLong(k + ":ack", 0L).apply()
    }
    @Synchronized fun latest(account: String, id: String): Boolean? {
        val k = key(account, id)
        return if (prefs.contains(k)) prefs.getBoolean(k, false) else null
    }
    @Synchronized fun desired(account: String, id: String, snapshotStartedAt: Long = Long.MAX_VALUE): Boolean? {
        val k = key(account, id); val value = latest(account, id) ?: return null
        val ack = prefs.getLong(k + ":ack", 0L)
        return if (ack == 0L || now() - ack < 30_000L || prefs.getLong(k + ":at", 0L) >= snapshotStartedAt) value else null
    }
    @Synchronized fun acknowledge(account: String, id: String, liked: Boolean) {
        if (latest(account, id) == liked) prefs.edit().putLong(key(account, id) + ":ack", now().coerceAtLeast(1L)).apply()
    }
    @Synchronized fun pending(account: String): List<Pair<String, Boolean>> {
        val prefix = prefix(account)
        return prefs.all.entries.asSequence().filter { it.key.startsWith(prefix) && it.value is Boolean }
            .filter { prefs.getLong(it.key + ":ack", 0L) == 0L }.map {
                String(Base64.decode(it.key.removePrefix(prefix), Base64.URL_SAFE), Charsets.UTF_8) to (it.value as Boolean)
            }.toList()
    }
}
