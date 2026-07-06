package com.example.uwbtag // 注意：修改为你的实际包名

data class UwbData(
    val distanceMeters: Float, // 距离（米），范围 0-10 米
    val azimuthDegrees: Float,  // 方位角（度），手机正前方为 0°，左负右正
    val elevationDegrees: Float = 0f, // 俯仰角（暂留扩展）
    val isReliable: Boolean = true // 数据是否可靠
)