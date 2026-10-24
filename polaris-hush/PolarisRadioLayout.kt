package app.hush.music.polaris

import android.content.Context

/**
 * Kotlin port of Polaris RadioPresets 16-slot swap behavior.
 * Persist by station ID so channel positions survive app restarts.
 * Empty slots can be selected with a long press too.
 */
class PolarisRadioLayout(context: Context) {
    private val prefs = context.getSharedPreferences("radio_presets", Context.MODE_PRIVATE)
    private val ids = RadioCatalog.stations.map { it.id }
    private val defaultSlots = listOf(
        "kr1", "kr2", "kr3", "kr4",
        "kr5", "kr6", null, null,
        "gallery", "wild949", "z100", "power1051",
        "kiis", "alt987", "myfm1043", null,
    )
    val slotCount: Int get() = 16

    fun slots(): List<String?> {
        val used = mutableSetOf<String>()
        val result = MutableList<String?>(slotCount) { slot ->
            val saved = prefs.getString("polaris_slot_$slot", defaultSlots[slot])
            saved?.takeIf { it in ids && used.add(it) }
        }
        // Invalid/corrupt prefs should not lose a station.
        ids.filterNot { it in used }.forEach { missing ->
            val blank = result.indexOfFirst { it == null }
            if (blank >= 0) result[blank] = missing
        }
        return result
    }

    /** Same occupied-cell swap / empty-cell move semantics as Polaris. */
    fun swap(slot: Int, stationId: String) {
        require(slot in 0 until slotCount)
        require(stationId in ids)
        val layout = slots().toMutableList()
        val from = layout.indexOf(stationId)
        if (from == slot) return
        val replaced = layout[slot]
        layout[slot] = stationId
        if (from >= 0) layout[from] = replaced
        prefs.edit().apply {
            layout.forEachIndexed { index, id -> putString("polaris_slot_$index", id) }
        }.apply()
    }
}
