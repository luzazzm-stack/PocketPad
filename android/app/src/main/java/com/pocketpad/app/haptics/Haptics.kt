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

    /** Vibration power, 0–100% — set from Settings, read per tick. */
    @Volatile
    var percent: Int = 75

    private val hasMotor = vibrator?.hasVibrator() == true

    /** One short tick — a button registered. */
    fun tick() {
        if (!hasMotor) return
        val v = vibrator ?: return
        val p = percent.coerceIn(0, 100)
        if (p == 0) return // 0% = silent

        // The percentage drives BOTH duration (10–150 ms) and amplitude
        // (1–255). Hand-built one-shots, not predefined effects: fallback
        // synthesis scrambles level ordering, and many motors (the realme
        // test device included) ignore amplitude — there, duration alone
        // carries the strength.
        val durationMs = 10L + (140L * p) / 100L
        val amplitude = ((255 * p) / 100).coerceIn(1, 255)
        val gameAttrs = AudioAttributes.Builder()
            .setUsage(AudioAttributes.USAGE_GAME)
            .build()
        when {
            // Plain vibrate() gets USAGE_TOUCH attributes, and OEMs scale touch
            // vibration by the system "touch feedback" intensity — observed at
            // 0.00 on realme/ColorOS, i.e. silenced. Tag as game/media instead,
            // like every mobile game with rumble does.
            Build.VERSION.SDK_INT >= 33 -> v.vibrate(
                VibrationEffect.createOneShot(durationMs, amplitude),
                VibrationAttributes.Builder()
                    .setUsage(VibrationAttributes.USAGE_MEDIA)
                    .build(),
            )
            Build.VERSION.SDK_INT >= 26 -> @Suppress("DEPRECATION") v.vibrate(
                VibrationEffect.createOneShot(durationMs, amplitude),
                gameAttrs,
            )
            // Android 6/7: no VibrationEffect — duration-only vibrate carries
            // the percentage on its own.
            else -> @Suppress("DEPRECATION") v.vibrate(durationMs, gameAttrs)
        }
    }
}

/** Provided once in MainActivity; screens call LocalHaptics.current.tick(). */
val LocalHaptics = staticCompositionLocalOf<Haptics> {
    error("Haptics not provided")
}
