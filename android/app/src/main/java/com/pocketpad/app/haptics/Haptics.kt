package com.pocketpad.app.haptics

import android.content.Context
import android.media.AudioAttributes
import android.os.Build
import android.os.VibrationAttributes
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import androidx.compose.runtime.staticCompositionLocalOf

/**
 * Direct vibrator access for button ticks.
 *
 * Compose's LocalHapticFeedback routes through View.performHapticFeedback,
 * which many OEMs (realme/ColorOS included) silently suppress when the system
 * "touch feedback" setting is off. Driving the Vibrator service directly works
 * regardless of that setting — the VIBRATE permission is all it needs.
 */
class Haptics(context: Context) {

    private val vibrator: Vibrator? =
        if (Build.VERSION.SDK_INT >= 31) {
            (context.getSystemService(Context.VIBRATOR_MANAGER_SERVICE) as? VibratorManager)
                ?.defaultVibrator
        } else {
            @Suppress("DEPRECATION")
            context.getSystemService(Context.VIBRATOR_SERVICE) as? Vibrator
        }

    /** 0 light · 1 medium · 2 strong — set from Settings, read per tick. */
    @Volatile
    var strength: Int = 1

    // Deliberately NOT the predefined TICK/CLICK/HEAVY_CLICK effects: on motors
    // without native support Android synthesizes them (fallback=true), and the
    // synthesized "tick" can land harder than the "click" — observed on the
    // realme test device, where Light felt stronger than Medium. Hand-built
    // one-shots keep the ordering honest everywhere: duration strictly grows,
    // and amplitude grows too on motors that support it.
    private val effects: Array<VibrationEffect>? =
        if (vibrator == null || !vibrator.hasVibrator()) null
        // Levels are percentages of max: Strong 100%, Medium 75%, Light ~37%.
        // Applied to BOTH duration and amplitude, because many motors (the
        // realme test device included) ignore amplitude — there, duration
        // alone carries the percentage.
        else arrayOf(
            VibrationEffect.createOneShot(56, 97),    // light  — ~37% of max
            VibrationEffect.createOneShot(112, 191),  // medium — 75% of max
            VibrationEffect.createOneShot(150, 255),  // strong — maxed out
        )

    /** One short tick — a button registered. */
    fun tick() {
        val fx = effects?.get(strength.coerceIn(0, 2)) ?: return
        val v = vibrator ?: return
        when {
            // Plain vibrate() gets USAGE_TOUCH attributes, and OEMs scale touch
            // vibration by the system "touch feedback" intensity — observed at
            // 0.00 on realme/ColorOS, i.e. silenced. Tag as game/media instead,
            // like every mobile game with rumble does.
            Build.VERSION.SDK_INT >= 33 -> v.vibrate(
                fx,
                VibrationAttributes.Builder()
                    .setUsage(VibrationAttributes.USAGE_MEDIA)
                    .build(),
            )
            else -> @Suppress("DEPRECATION") v.vibrate(
                fx,
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_GAME)
                    .build(),
            )
        }
    }
}

/** Provided once in MainActivity; screens call LocalHaptics.current.tick(). */
val LocalHaptics = staticCompositionLocalOf<Haptics> {
    error("Haptics not provided")
}
