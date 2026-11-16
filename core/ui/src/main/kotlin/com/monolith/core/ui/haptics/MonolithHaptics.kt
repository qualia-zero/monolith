package com.monolith.core.ui.haptics

import android.content.Context
import android.os.Build
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import android.view.HapticFeedbackConstants
import android.view.View
import com.monolith.core.common.AppSettingsStore

object MonolithHaptics {

    fun tap(view: View) {
        val settings = AppSettingsStore.settings.value
        if (!settings.hapticsEnabled || !settings.hapticTapClick) return
        val constant = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O_MR1) {
            HapticFeedbackConstants.KEYBOARD_TAP
        } else {
            HapticFeedbackConstants.VIRTUAL_KEY
        }
        view.performHapticFeedback(constant)
    }

    fun dragLift(view: View) {
        val settings = AppSettingsStore.settings.value
        if (!settings.hapticsEnabled || !settings.hapticDragLift) return
        view.performHapticFeedback(HapticFeedbackConstants.LONG_PRESS)
    }

    fun sliderTick(view: View) {
        val settings = AppSettingsStore.settings.value
        if (!settings.hapticsEnabled || !settings.hapticSliderTick) return
        val constant = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O_MR1) {
            HapticFeedbackConstants.TEXT_HANDLE_MOVE
        } else {
            HapticFeedbackConstants.CLOCK_TICK
        }
        view.performHapticFeedback(constant)
    }

    fun successPulse(context: Context) {
        val settings = AppSettingsStore.settings.value
        if (!settings.hapticsEnabled || !settings.hapticPulseFeedback) return
        val vibrator = resolveVibrator(context) ?: return
        if (!vibrator.hasVibrator()) return
        val effect = VibrationEffect.createWaveform(
            longArrayOf(0L, 18L, 45L, 28L),
            intArrayOf(0, 140, 0, 220),
            -1,
        )
        vibrator.vibrate(effect)
    }

    private fun resolveVibrator(context: Context): Vibrator? {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            context.getSystemService(VibratorManager::class.java)?.defaultVibrator
        } else {
            @Suppress("DEPRECATION")
            context.getSystemService(Context.VIBRATOR_SERVICE) as? Vibrator
        }
    }
}
