package com.pocketpad.app.settings

import android.content.SharedPreferences
import org.json.JSONObject

/** Every control the player can move and resize. */
enum class PadElement { DPAD, FACE, LB, RB, LT, RT, BACK, START, TOGGLE }

/**
 * One control's placement: a dp offset from its default anchor, and a size
 * multiplier. Defaults mean "exactly where the app puts it".
 */
data class ElementLayout(
    val x: Float = 0f,
    val y: Float = 0f,
    val scale: Float = 1f,
)

/**
 * Where every control sits and how large it is, plus which shape the left
 * control takes. Stored as one JSON blob so adding a control later does not
 * need a preferences migration.
 */
data class PadLayout(
    val elements: Map<PadElement, ElementLayout> = emptyMap(),
    /** Left control: false = d-pad cross, true = analog stick. */
    val stickMode: Boolean = false,
) {
    fun of(e: PadElement): ElementLayout = elements[e] ?: ElementLayout()

    fun with(e: PadElement, block: (ElementLayout) -> ElementLayout): PadLayout =
        copy(elements = elements + (e to block(of(e))))

    fun toJson(): String {
        val els = JSONObject()
        for ((e, l) in elements) {
            els.put(e.name, JSONObject().put("x", l.x).put("y", l.y).put("s", l.scale))
        }
        return JSONObject()
            .put("v", VERSION)
            .put("els", els)
            .put("stick", stickMode)
            .toString()
    }

    companion object {
        const val MIN_SCALE = 0.7f
        const val MAX_SCALE = 1.5f
        private const val VERSION = 2

        fun fromJson(s: String?): PadLayout {
            if (s.isNullOrBlank()) return PadLayout()
            return runCatching {
                val o = JSONObject(s)
                if (o.optInt("v", 1) >= 2) parseV2(o) else parseV1(o)
            }.getOrDefault(PadLayout())
        }

        private fun parseV2(o: JSONObject): PadLayout {
            val els = o.optJSONObject("els") ?: JSONObject()
            val map = mutableMapOf<PadElement, ElementLayout>()
            val keys = els.keys()
            while (keys.hasNext()) {
                val k = keys.next()
                val e = runCatching { PadElement.valueOf(k) }.getOrNull() ?: continue
                val j = els.optJSONObject(k) ?: continue
                map[e] = ElementLayout(
                    x = j.optDouble("x", 0.0).toFloat(),
                    y = j.optDouble("y", 0.0).toFloat(),
                    scale = j.optDouble("s", 1.0).toFloat().coerceIn(MIN_SCALE, MAX_SCALE),
                )
            }
            return PadLayout(map, o.optBoolean("stick", false))
        }

        /** v1 could only move the d-pad and the face cluster; keep those. */
        private fun parseV1(o: JSONObject): PadLayout = PadLayout(
            mapOf(
                PadElement.DPAD to ElementLayout(
                    x = o.optDouble("dx", 0.0).toFloat(),
                    y = o.optDouble("dy", 0.0).toFloat(),
                    scale = o.optDouble("ds", 1.0).toFloat().coerceIn(MIN_SCALE, MAX_SCALE),
                ),
                PadElement.FACE to ElementLayout(
                    x = o.optDouble("fx", 0.0).toFloat(),
                    y = o.optDouble("fy", 0.0).toFloat(),
                    scale = o.optDouble("fs", 1.0).toFloat().coerceIn(MIN_SCALE, MAX_SCALE),
                ),
            )
        )
    }
}

/** Everything the Settings screen controls. */
data class AppSettings(
    val layout: PadLayout = PadLayout(),
    val haptics: Boolean = true,
    val hapticPercent: Int = 75, // 0–100% vibration power
    val mouseSensitivity: Float = 1.5f, // 0.5 slow … 3.0 fast
)

class SettingsStore(private val prefs: SharedPreferences) {

    fun load(): AppSettings = AppSettings(
        layout = PadLayout.fromJson(prefs.getString("layout", null)),
        haptics = prefs.getBoolean("haptics", true),
        hapticPercent = when {
            prefs.contains("hapticPct") -> prefs.getInt("hapticPct", 75)
            // migrate from the old 3-level setting
            prefs.contains("hapticStr") -> when (prefs.getInt("hapticStr", 1)) {
                0 -> 37; 2 -> 100; else -> 75
            }
            else -> 75
        }.coerceIn(0, 100),
        mouseSensitivity = prefs.getFloat("mouseSens", 1.5f).coerceIn(0.5f, 3f),
    )

    fun save(s: AppSettings) {
        prefs.edit()
            .putString("layout", s.layout.toJson())
            .putBoolean("haptics", s.haptics)
            .putInt("hapticPct", s.hapticPercent)
            .putFloat("mouseSens", s.mouseSensitivity)
            .apply()
    }
}
