package com.example.telepathytag

import android.content.Context
import android.os.Build
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager

class FeedbackManager(context: Context) {

    private val vibrator: Vibrator = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
        val vibratorManager = context.getSystemService(Context.VIBRATOR_MANAGER_SERVICE) as VibratorManager
        vibratorManager.defaultVibrator
    } else {
        @Suppress("DEPRECATION")
        context.getSystemService(Context.VIBRATOR_SERVICE) as Vibrator
    }

    private var isVibrating = false

    fun triggerDistanceFeedback(distanceMeters: Float) {
        if (distanceMeters <= 0.0f) {
            stop()
            return
        }

        if (distanceMeters < 1.0f) {
            if (!isVibrating) {
                isVibrating = true
                val timings = longArrayOf(0, 40, 120)
                val amplitudes = intArrayOf(0, 180, 0)

                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    vibrator.vibrate(VibrationEffect.createWaveform(timings, amplitudes, 0))
                } else {
                    @Suppress("DEPRECATION")
                    vibrator.vibrate(timings, 0)
                }
            }
        } else {
            stop()
        }
    }

    fun stop() {
        if (isVibrating) {
            vibrator.cancel()
            isVibrating = false
        }
    }
}
