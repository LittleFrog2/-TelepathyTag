package com.example.uwbtag

import android.Manifest
import android.annotation.SuppressLint
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.bluetooth.BluetoothManager
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanFilter
import android.bluetooth.le.ScanResult
import android.bluetooth.le.ScanSettings
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.IBinder
import android.os.ParcelUuid
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import java.util.UUID

class FindPhoneScanService : Service() {
    private val qnisServiceUuid = UUID.fromString("2E938FD0-6A61-11ED-A1EB-0242AC120002")
    private val feedbackManager by lazy { FeedbackManager(this) }
    private var lastSeq: Int? = null
    private var isScanning = false

    private val scanCallback = object : ScanCallback() {
        override fun onScanResult(callbackType: Int, result: ScanResult) {
            handleScanResult(result)
        }

        override fun onBatchScanResults(results: MutableList<ScanResult>) {
            results.forEach(::handleScanResult)
        }

        override fun onScanFailed(errorCode: Int) {
            Log.e(TAG, "Find-phone BLE scan failed: $errorCode")
        }
    }

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
        startForeground(NOTIFICATION_ID, buildNotification())
        startBleScan()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        startBleScan()
        return START_STICKY
    }

    override fun onDestroy() {
        stopBleScan()
        feedbackManager.stopAll()
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    @SuppressLint("MissingPermission")
    private fun startBleScan() {
        if (isScanning || !hasBleScanPermission()) {
            return
        }
        val scanner = (getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager)
            .adapter
            ?.bluetoothLeScanner ?: return

        val filters = listOf(
            ScanFilter.Builder()
                .setServiceUuid(ParcelUuid(qnisServiceUuid))
                .build()
        )
        val settings = ScanSettings.Builder()
            .setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY)
            .build()

        scanner.startScan(filters, settings, scanCallback)
        isScanning = true
        Log.i(TAG, "Find-phone background scan started")
    }

    @SuppressLint("MissingPermission")
    private fun stopBleScan() {
        if (!isScanning || !hasBleScanPermission()) {
            return
        }
        val scanner = (getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager)
            .adapter
            ?.bluetoothLeScanner ?: return
        scanner.stopScan(scanCallback)
        isScanning = false
    }

    private fun handleScanResult(result: ScanResult) {
        val data = result.scanRecord?.getManufacturerSpecificData(FIND_PHONE_COMPANY_ID) ?: return
        if (data.size < 5 || data[0] != 'Q'.code.toByte() || data[1] != 'F'.code.toByte() || data[2] != 'P'.code.toByte()) {
            return
        }

        val active = data[3].toInt() != 0
        val seq = data[4].toInt() and 0xFF
        if (lastSeq == seq) {
            return
        }
        lastSeq = seq

        Log.i(TAG, "Find-phone adv event: active=$active seq=$seq rssi=${result.rssi}")
        feedbackManager.setFindPhoneAlertActive(active)
    }

    private fun hasBleScanPermission(): Boolean {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            ContextCompat.checkSelfPermission(this, Manifest.permission.BLUETOOTH_SCAN) == PackageManager.PERMISSION_GRANTED
        } else {
            ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED
        }
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) {
            return
        }
        val channel = NotificationChannel(
            CHANNEL_ID,
            "Tag 按键监听",
            NotificationManager.IMPORTANCE_LOW
        )
        channel.description = "后台监听 Tag 的找手机广播"
        getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
    }

    private fun buildNotification() = NotificationCompat.Builder(this, CHANNEL_ID)
        .setSmallIcon(android.R.drawable.stat_sys_data_bluetooth)
        .setContentTitle("正在监听 Tag 按键")
        .setContentText("按 Tag 的 SW2 可触发或取消手机响铃")
        .setOngoing(true)
        .setPriority(NotificationCompat.PRIORITY_LOW)
        .build()

    companion object {
        private const val TAG = "FindPhoneScanService"
        private const val CHANNEL_ID = "find_phone_scan"
        private const val NOTIFICATION_ID = 4102
        private const val FIND_PHONE_COMPANY_ID = 0xFFFF
    }
}
