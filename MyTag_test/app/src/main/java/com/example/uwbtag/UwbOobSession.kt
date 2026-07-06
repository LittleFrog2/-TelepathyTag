package com.example.uwbtag

import android.content.Context
import android.content.pm.PackageManager
import androidx.core.uwb.UwbAddress
import androidx.core.uwb.UwbComplexChannel
import java.security.SecureRandom

private const val STATIC_STS_KEY_INFO_LEN = 8

data class UwbOobSession(
    val sessionId: Long,
    val phoneShortAddress: Int,
    val tagShortAddress: Int,
    val channel: Int,
    val preambleCode: Int,
    val slotDurationRstu: Int,
    val blockDurationMs: Int,
    val roundDurationSlots: Int,
    val updateRateHz: Int,
    val stsConfig: Int,
    val sessionKey: ByteArray,
    val isUwbSupported: Boolean,
    val isAndroidUwbSessionBacked: Boolean
) {
    fun toStartPacket(): ByteArray {
        require(sessionKey.size == SESSION_KEY_LEN)
        val out = ByteArray(START_PACKET_LEN)
        out[0] = 'U'.code.toByte()
        out[1] = 'T'.code.toByte()
        out[2] = 'O'.code.toByte()
        out[3] = 'B'.code.toByte()
        out[4] = VERSION
        out[5] = MSG_START_SESSION
        out.putUInt16Le(6, PAYLOAD_LEN)
        out.putUInt32Le(8, sessionId)
        out.putUInt16Le(12, phoneShortAddress)
        out.putUInt16Le(14, tagShortAddress)
        out[16] = channel.toByte()
        out[17] = preambleCode.toByte()
        out.putUInt16Le(18, slotDurationRstu)
        out.putUInt16Le(20, blockDurationMs)
        out[22] = roundDurationSlots.toByte()
        out[23] = updateRateHz.toByte()
        out[24] = stsConfig.toByte()
        out[25] = SESSION_KEY_LEN.toByte()
        sessionKey.copyInto(out, destinationOffset = 26)
        return out
    }

    fun summary(): String {
        return "session=0x${sessionId.toString(16)}, phone=0x${phoneShortAddress.toString(16)}, tag=0x${tagShortAddress.toString(16)}, ch=$channel, preamble=$preambleCode"
    }

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is UwbOobSession) return false
        return sessionId == other.sessionId &&
            phoneShortAddress == other.phoneShortAddress &&
            tagShortAddress == other.tagShortAddress &&
            channel == other.channel &&
            preambleCode == other.preambleCode &&
            slotDurationRstu == other.slotDurationRstu &&
            blockDurationMs == other.blockDurationMs &&
            roundDurationSlots == other.roundDurationSlots &&
            updateRateHz == other.updateRateHz &&
            stsConfig == other.stsConfig &&
            sessionKey.contentEquals(other.sessionKey) &&
            isUwbSupported == other.isUwbSupported &&
            isAndroidUwbSessionBacked == other.isAndroidUwbSessionBacked
    }

    override fun hashCode(): Int {
        var result = sessionId.hashCode()
        result = 31 * result + phoneShortAddress
        result = 31 * result + tagShortAddress
        result = 31 * result + channel
        result = 31 * result + preambleCode
        result = 31 * result + slotDurationRstu
        result = 31 * result + blockDurationMs
        result = 31 * result + roundDurationSlots
        result = 31 * result + updateRateHz
        result = 31 * result + stsConfig
        result = 31 * result + sessionKey.contentHashCode()
        result = 31 * result + isUwbSupported.hashCode()
        result = 31 * result + isAndroidUwbSessionBacked.hashCode()
        return result
    }

    companion object {
        const val START_PACKET_LEN = 34
        const val TAG_SHORT_ADDRESS = 0x0100
        private const val PAYLOAD_LEN = START_PACKET_LEN - 8
        private const val SESSION_KEY_LEN = STATIC_STS_KEY_INFO_LEN
        private const val STATIC_STS_CONFIG = 0
        private const val VERSION: Byte = 1
        private const val MSG_START_SESSION: Byte = 2
        private val DEBUG_SESSION_KEY = ByteArray(SESSION_KEY_LEN) { 0x00.toByte() }

        fun create(context: Context): UwbOobSession {
            val random = SecureRandom()
            val key = DEBUG_SESSION_KEY.copyOf()

            val sessionId = random.nextInt().toLong() and 0xFFFF_FFFFL
            val phoneAddr = 0x0200 or random.nextInt(0x00FF)
            val uwbSupported = context.packageManager.hasSystemFeature(PackageManager.FEATURE_UWB)

            return UwbOobSession(
                sessionId = if (sessionId == 0L) 0x11223344L else sessionId,
                phoneShortAddress = phoneAddr,
                tagShortAddress = TAG_SHORT_ADDRESS,
                channel = 9,
                preambleCode = 11,
                slotDurationRstu = 2400,
                blockDurationMs = 240,
                roundDurationSlots = 6,
                updateRateHz = 4,
                stsConfig = STATIC_STS_CONFIG,
                sessionKey = key,
                isUwbSupported = uwbSupported,
                isAndroidUwbSessionBacked = false
            )
        }

        fun createFallbackForBoardOnly(context: Context): UwbOobSession {
            val key = DEBUG_SESSION_KEY.copyOf()
            val uwbSupported = context.packageManager.hasSystemFeature(PackageManager.FEATURE_UWB)

            return UwbOobSession(
                sessionId = 0x11223344L,
                phoneShortAddress = 0x0201,
                tagShortAddress = TAG_SHORT_ADDRESS,
                channel = 9,
                preambleCode = 11,
                slotDurationRstu = 2400,
                blockDurationMs = 240,
                roundDurationSlots = 6,
                updateRateHz = 4,
                stsConfig = STATIC_STS_CONFIG,
                sessionKey = key,
                isUwbSupported = uwbSupported,
                isAndroidUwbSessionBacked = false
            )
        }

        fun createFromAndroidController(
            context: Context,
            localAddress: UwbAddress,
            complexChannel: UwbComplexChannel
        ): UwbOobSession {
            val random = SecureRandom()
            val key = DEBUG_SESSION_KEY.copyOf()

            val sessionId = random.nextInt().toLong() and 0xFFFF_FFFFL
            val phoneAddr = localAddress.toShortAddress()
            val uwbSupported = context.packageManager.hasSystemFeature(PackageManager.FEATURE_UWB)

            return UwbOobSession(
                sessionId = if (sessionId == 0L) 0x11223344L else sessionId,
                phoneShortAddress = phoneAddr,
                tagShortAddress = TAG_SHORT_ADDRESS,
                channel = complexChannel.channel,
                preambleCode = complexChannel.preambleIndex,
                slotDurationRstu = 2400,
                blockDurationMs = 240,
                roundDurationSlots = 6,
                updateRateHz = 4,
                stsConfig = STATIC_STS_CONFIG,
                sessionKey = key,
                isUwbSupported = uwbSupported,
                isAndroidUwbSessionBacked = true
            )
        }

        fun isAck(bytes: ByteArray): Boolean {
            return bytes.size >= 13 &&
                bytes[0] == 'U'.code.toByte() &&
                bytes[1] == 'T'.code.toByte() &&
                bytes[2] == 'O'.code.toByte() &&
                bytes[3] == 'B'.code.toByte() &&
                bytes[4] == VERSION &&
                bytes[5] == MSG_ACK
        }

        fun ackStatus(bytes: ByteArray): Int = bytes[8].toInt() and 0xFF

        fun ackSessionId(bytes: ByteArray): Long {
            return (bytes[9].toLong() and 0xFF) or
                ((bytes[10].toLong() and 0xFF) shl 8) or
                ((bytes[11].toLong() and 0xFF) shl 16) or
                ((bytes[12].toLong() and 0xFF) shl 24)
        }

        private const val MSG_ACK: Byte = 0x82.toByte()
    }
}

fun UwbOobSession.staticStsKeyInfo(): ByteArray = sessionKey.copyOf(STATIC_STS_KEY_INFO_LEN)

fun UwbOobSession.tagUwbAddress(): UwbAddress {
    return UwbAddress(
        byteArrayOf(
            (tagShortAddress and 0xFF).toByte(),
            ((tagShortAddress ushr 8) and 0xFF).toByte()
        )
    )
}

fun UwbOobSession.toUwbComplexChannel(): UwbComplexChannel {
    return UwbComplexChannel(channel, preambleCode)
}

private fun UwbAddress.toShortAddress(): Int {
    require(address.size >= 2) { "UWB local address must contain at least 2 bytes" }
    return (address[0].toInt() and 0xFF) or ((address[1].toInt() and 0xFF) shl 8)
}

private fun ByteArray.putUInt16Le(offset: Int, value: Int) {
    this[offset] = (value and 0xFF).toByte()
    this[offset + 1] = ((value ushr 8) and 0xFF).toByte()
}

private fun ByteArray.putUInt32Le(offset: Int, value: Long) {
    this[offset] = (value and 0xFF).toByte()
    this[offset + 1] = ((value ushr 8) and 0xFF).toByte()
    this[offset + 2] = ((value ushr 16) and 0xFF).toByte()
    this[offset + 3] = ((value ushr 24) and 0xFF).toByte()
}
