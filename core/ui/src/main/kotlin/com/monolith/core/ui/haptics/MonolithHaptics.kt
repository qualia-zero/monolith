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

    fun folderNav(context: Context, view: View) {
        val settings = AppSettingsStore.settings.value
        if (!settings.hapticsEnabled || !settings.hapticFolderNav) return
        val vibrated = vibrateWaveform(
            context = context,
            timings = longArrayOf(0L, 12L),
            amplitudes = intArrayOf(0, 150),
        )
        if (!vibrated) {
            view.performHapticFeedback(HapticFeedbackConstants.CONTEXT_CLICK)
        }
    }

    /**
     * Distinct ascending ramp + crisp kick when launching a media file or viewer.
     */
    fun launchItem(context: Context) {
        val settings = AppSettingsStore.settings.value
        if (!settings.hapticsEnabled || !settings.hapticLaunchItem) return
        vibrateWaveform(
            context = context,
            timings = longArrayOf(0L, 14L, 28L, 22L, 32L, 30L),
            amplitudes = intArrayOf(0, 90, 0, 165, 0, 255),
        )
    }

    fun successPulse(context: Context) {
        val settings = AppSettingsStore.settings.value
        if (!settings.hapticsEnabled || !settings.hapticPulseFeedback) return
        vibrateWaveform(
            context = context,
            timings = longArrayOf(0L, 18L, 45L, 28L),
            amplitudes = intArrayOf(0, 140, 0, 220),
        )
    }

    private fun vibrateWaveform(
        context: Context,
        timings: LongArray,
        amplitudes: IntArray,
    ): Boolean {
        return runCatching {
            val vibrator = resolveVibrator(context) ?: return@runCatching false
            if (!vibrator.hasVibrator()) return@runCatching false
            val effect = VibrationEffect.createWaveform(timings, amplitudes, -1)
            vibrator.vibrate(effect)
            true
        }.getOrDefault(false)
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
