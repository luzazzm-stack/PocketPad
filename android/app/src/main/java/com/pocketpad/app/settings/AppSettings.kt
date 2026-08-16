package com.pocketpad.app.settings

import android.content.SharedPreferences
import org.json.JSONObject

/**
 * Where the two big control clusters sit and how large they are.
 * Offsets are dp deltas from the default position; scale multiplies size.
 */
data class PadLayout(
    val dpadX: Float = 0f,
    val dpadY: Float = 0f,
    val dpadScale: Float = 1f,
    val faceX: Float = 0f,
    val faceY: Float = 0f,
    val faceScale: Float = 1f,
) {
    fun toJson(): String = JSONObject()
        .put("dx", dpadX).put("dy", dpadY).put("ds", dpadScale)
        .put("fx", faceX).put("fy", faceY).put("fs", faceScale)
        .toString()

    companion object {
        fun fromJson(s: String?): PadLayout {
            if (s.isNullOrBlank()) return PadLayout()
            return runCatching {
                val o = JSONObject(s)
                PadLayout(
                    dpadX = o.optDouble("dx", 0.0).toFloat(),
                    dpadY = o.optDouble("dy", 0.0).toFloat(),
                    dpadScale = o.optDouble("ds", 1.0).toFloat().coerceIn(0.7f, 1.5f),
                    faceX = o.optDouble("fx", 0.0).toFloat(),
                    faceY = o.optDouble("fy", 0.0).toFloat(),
                    faceScale = o.optDouble("fs", 1.0).toFloat().coerceIn(0.7f, 1.5f),
                )
            }.getOrDefault(PadLayout())
        }
    }
}

/** Everything the Settings screen controls. */
data class AppSettings(
    val layout: PadLayout = PadLayout(),
    val haptics: Boolean = true,
    val hapticStrength: Int = 1, // 0 light · 1 medium · 2 strong
    val mouseSensitivity: Float = 1.5f, // 0.5 slow … 3.0 fast
)

class SettingsStore(private val prefs: SharedPreferences) {

    fun load(): AppSettings = AppSettings(
        layout = PadLayout.fromJson(prefs.getString("layout", null)),
        haptics = prefs.getBoolean("haptics", true),
        hapticStrength = prefs.getInt("hapticStr", 1).coerceIn(0, 2),
        mouseSensitivity = prefs.getFloat("mouseSens", 1.5f).coerceIn(0.5f, 3f),
    )

    fun save(s: AppSettings) {
        prefs.edit()
            .putString("layout", s.layout.toJson())
            .putBoolean("haptics", s.haptics)
            .putInt("hapticStr", s.hapticStrength)
            .putFloat("mouseSens", s.mouseSensitivity)
            .apply()
    }
}
