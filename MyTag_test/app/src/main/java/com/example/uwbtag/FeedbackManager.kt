package com.example.uwbtag

import android.content.Context
import android.media.AudioAttributes
import android.media.Ringtone
import android.media.RingtoneManager
import android.os.Build
import android.os.Handler
import android.os.Looper
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
    private val appContext = context.applicationContext
    private val handler = Handler(Looper.getMainLooper())
    private var findPhoneRingtone: Ringtone? = null

    private var isDistanceVibrating = false
    private var isFindPhoneAlertActive = false

    // 🎯 动作 4：当距离 < 1米 时触发的高级触觉反馈
    fun triggerDistanceFeedback(distanceMeters: Float) {
        if (isFindPhoneAlertActive) {
            return
        }

        if (distanceMeters <= 0.0f) {
            stopDistanceFeedback()
            return
        }

        if (distanceMeters < 1.0f) {
            if (!isDistanceVibrating) {
                isDistanceVibrating = true
                // 模拟 AirTag 连续清脆“哒哒哒”敲击：震动 40ms，停 120ms
                val timings = longArrayOf(0, 40, 120)
                val amplitudes = intArrayOf(0, 180, 0) // 180 为中等偏强的舒适振幅

                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    vibrator.vibrate(VibrationEffect.createWaveform(timings, amplitudes, 0)) // 0 代表循环
                } else {
                    @Suppress("DEPRECATION")
                    vibrator.vibrate(timings, 0)
                }
            }
        } else {
            // 远离 1 米后自动安静
            stopDistanceFeedback()
        }
    }

    fun stopDistanceFeedback() {
        if (isDistanceVibrating && !isFindPhoneAlertActive) {
            vibrator.cancel()
        }
        isDistanceVibrating = false
    }

    fun toggleFindPhoneAlert(): Boolean {
        return if (isFindPhoneAlertActive) {
            stopFindPhoneAlert()
            false
        } else {
            startFindPhoneAlert()
            true
        }
    }

    fun setFindPhoneAlertActive(active: Boolean) {
        if (active) {
            if (!isFindPhoneAlertActive) {
                startFindPhoneAlert()
            }
        } else {
            stopFindPhoneAlert()
        }
    }

    private fun startFindPhoneAlert() {
        stopDistanceFeedback()
        isFindPhoneAlertActive = true

        val ringtoneUri = RingtoneManager.getDefaultUri(RingtoneManager.TYPE_ALARM)
            ?: RingtoneManager.getDefaultUri(RingtoneManager.TYPE_RINGTONE)
            ?: RingtoneManager.getDefaultUri(RingtoneManager.TYPE_NOTIFICATION)
        findPhoneRingtone = ringtoneUri?.let { RingtoneManager.getRingtone(appContext, it) }

        findPhoneRingtone?.audioAttributes = AudioAttributes.Builder()
            .setUsage(AudioAttributes.USAGE_ALARM)
            .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
            .build()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            findPhoneRingtone?.isLooping = true
        }
        findPhoneRingtone?.play()

        val timings = longArrayOf(0, 250, 120, 250, 500)
        val amplitudes = intArrayOf(0, 255, 0, 220, 0)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            vibrator.vibrate(VibrationEffect.createWaveform(timings, amplitudes, 0))
        } else {
            @Suppress("DEPRECATION")
            vibrator.vibrate(timings, 0)
        }

        handler.postDelayed({ stopFindPhoneAlert() }, 8000)
    }

    fun stopFindPhoneAlert() {
        handler.removeCallbacksAndMessages(null)
        findPhoneRingtone?.stop()
        findPhoneRingtone = null
        isFindPhoneAlertActive = false
        isDistanceVibrating = false
        vibrator.cancel()
    }

    fun stopAll() {
        stopDistanceFeedback()
        stopFindPhoneAlert()
    }
}
