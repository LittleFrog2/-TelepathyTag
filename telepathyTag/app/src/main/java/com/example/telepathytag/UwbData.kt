package com.example.telepathytag

data class UwbData(
    val distanceMeters: Float,
    val azimuthDegrees: Float,
    val elevationDegrees: Float = 0f,
    val isReliable: Boolean = true
)
