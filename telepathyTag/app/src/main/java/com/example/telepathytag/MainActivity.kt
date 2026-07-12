package com.example.telepathytag

import android.Manifest
import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothManager
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanFilter
import android.bluetooth.le.ScanResult
import android.bluetooth.le.ScanSettings
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.ParcelUuid
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.EaseInOutCubic
import androidx.compose.animation.core.EaseOutCubic
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.slideInVertically
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.draw.scale
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.Fill
import androidx.compose.ui.graphics.drawscope.rotate as rotateDraw
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import com.example.telepathytag.ui.theme.*
import java.util.UUID
import kotlin.math.*
import kotlin.random.Random

// ============================================================
// Particle Data for Precision Finding (State 1)
// ============================================================
private data class FinderParticle(
    val orbitRadius: Float,  // radial distance from center (fraction of canvas radius)
    val angle: Float,        // starting angular position (radians)
    val size: Float,         // dot size in dp
    val phase: Float,        // alpha animation phase offset
    val speed: Float,        // orbital angular speed multiplier
    val direction: Float,    // +1.0 = clockwise, -1.0 = counter-clockwise
    val arcFraction: Float   // position along the arc (0.0–1.0) for convergence
)

private fun generateFinderParticles(count: Int, maxRadiusFraction: Float): List<FinderParticle> {
    return List(count) {
        val angle = Random.nextFloat() * 2f * PI.toFloat()
        // All particles near the edge (outer 20% of radius)
        val orbitRadius = maxRadiusFraction * (0.80f + Random.nextFloat() * 0.20f)
        FinderParticle(
            orbitRadius = orbitRadius,
            angle = angle,
            size = Random.nextFloat() * 2.5f + 1.2f,
            phase = Random.nextFloat() * 2f * PI.toFloat(),
            speed = 0.1f + Random.nextFloat() * 0.4f,   // slower, gentle orbit
            direction = if (Random.nextBoolean()) 1f else -1f,  // random CW or CCW
            arcFraction = Random.nextFloat()  // random position along the arc
        )
    }
}

class MainActivity : ComponentActivity() {
    private val qnisServiceUuid = UUID.fromString("2E938FD0-6A61-11ED-A1EB-0242AC120002")
    private val bleManager by lazy { UwbBleManager(this) }
    private val feedbackManager by lazy { FeedbackManager(this) }
    private val tagRepository by lazy { TagRepository(this) }
    private var bluetoothAdapter: BluetoothAdapter? = null
    private val scannedDevices = mutableStateListOf<ScanResult>()
    private val isScanning = mutableStateOf(false)
    private val handler = Handler(Looper.getMainLooper())

    private val requestPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { permissions ->
        if (!permissions.all { it.value }) {
            Toast.makeText(this, "需要授权才可使用雷达寻物", Toast.LENGTH_LONG).show()
        } else {
            startFindPhoneScanService()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val bluetoothManager = getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager
        bluetoothAdapter = bluetoothManager.adapter

        if (checkAndRequestPermissions()) {
            startFindPhoneScanService()
        }

        setContent {
            TelepathyTagTheme {
                val currentMac by bleManager.connectedMac.collectAsState()
                val connState by bleManager.connectionState.collectAsState()
                val debugLogs = remember { mutableStateListOf<String>() }
                val tagBindings by tagRepository.bindings.collectAsState()

                val uwbData by bleManager.uwbDataFlow.collectAsState(initial = UwbRealData(0.0f, 0.0f))
                val findingStatus by bleManager.findingStatus.collectAsState()

                // Collect BLE debug logs
                LaunchedEffect(Unit) {
                    bleManager.testLogFlow.collect { message ->
                        debugLogs.add(0, message)
                        if (debugLogs.size > 80) {
                            debugLogs.removeAt(debugLogs.lastIndex)
                        }
                    }
                }

                // Haptic feedback when ranging succeeds and distance < 1m
                LaunchedEffect(uwbData.distanceMeters, connState, findingStatus) {
                    if (connState == "已连接" && findingStatus == FindingStatus.SUCCESS && uwbData.distanceMeters > 0.05f) {
                        feedbackManager.triggerDistanceFeedback(uwbData.distanceMeters)
                    } else {
                        feedbackManager.stopDistanceFeedback()
                    }
                }

                // Tag → Phone: SW2 button triggers phone ring via findPhoneEventFlow
                LaunchedEffect(Unit) {
                    bleManager.findPhoneEventFlow.collect {
                        val started = feedbackManager.toggleFindPhoneAlert()
                        val message = if (started) "Tag 按键已触发手机响铃" else "Tag 按键已取消手机响铃"
                        Toast.makeText(this@MainActivity, message, Toast.LENGTH_SHORT).show()
                    }
                }

                Surface(modifier = Modifier.fillMaxSize(), color = BackgroundBlack) {
                    if (currentMac.isEmpty()) {
                        BleScanScreen(
                            devices = scannedDevices,
                            isScanning = isScanning.value,
                            tagBindings = tagBindings,
                            onStartScan = { startLeScan() },
                            onDeviceClick = { mac ->
                                stopLeScan()
                                bleManager.connectToDevice(mac)
                            }
                        )
                    } else {
                        HaloTagRadarScreen(
                            connState = connState,
                            connectedMac = currentMac,
                            uwbData = uwbData,
                            findingStatus = findingStatus,
                            debugLogs = debugLogs,
                            tagBindings = tagBindings,
                            onStartFindingClick = {
                                bleManager.sendStartFindingCmd()
                            },
                            onDisconnect = {
                                feedbackManager.stopAll()
                                bleManager.disconnect()
                            },
                            onConnectSavedDevice = { mac ->
                                feedbackManager.stopAll()
                                bleManager.connectToDevice(mac)
                            },
                            onAddBinding = { mac, name ->
                                tagRepository.addBinding(mac, name)
                            }
                        )
                    }
                }
            }
        }
    }

    private val leScanCallback = object : ScanCallback() {
        override fun onScanResult(callbackType: Int, result: ScanResult) {
            val deviceAddress = result.device.address
            if (!scannedDevices.any { it.device.address == deviceAddress }) {
                scannedDevices.add(result)
            }
        }
    }

    @SuppressLint("MissingPermission")
    private fun startLeScan() {
        if (isScanning.value || bluetoothAdapter == null || !bluetoothAdapter!!.isEnabled) return
        scannedDevices.clear()
        isScanning.value = true
        handler.removeCallbacksAndMessages(null)
        handler.postDelayed({ stopLeScan() }, 5000)
        val filters = listOf(
            ScanFilter.Builder()
                .setServiceUuid(ParcelUuid(qnisServiceUuid))
                .build()
        )
        val settings = ScanSettings.Builder()
            .setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY)
            .build()
        bluetoothAdapter!!.bluetoothLeScanner?.startScan(filters, settings, leScanCallback)
    }

    @SuppressLint("MissingPermission")
    private fun stopLeScan() {
        if (!isScanning.value) return
        isScanning.value = false
        bluetoothAdapter?.bluetoothLeScanner?.stopScan(leScanCallback)
    }

    private fun checkAndRequestPermissions(): Boolean {
        val required = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            buildList {
                add(Manifest.permission.BLUETOOTH_CONNECT)
                add(Manifest.permission.BLUETOOTH_SCAN)
                add(Manifest.permission.ACCESS_FINE_LOCATION)
                add(Manifest.permission.UWB_RANGING)
                add(Manifest.permission.VIBRATE)
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                    add(Manifest.permission.POST_NOTIFICATIONS)
                }
            }.toTypedArray()
        } else {
            arrayOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.VIBRATE)
        }
        val missing = required.filter {
            ContextCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED
        }.toTypedArray()
        if (missing.isNotEmpty()) {
            requestPermissionLauncher.launch(missing)
            return false
        }
        return true
    }

    private fun startFindPhoneScanService() {
        val intent = Intent(this, FindPhoneScanService::class.java)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            startForegroundService(intent)
        } else {
            startService(intent)
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        feedbackManager.stopAll()
        bleManager.disconnect()
    }
}

// ============================================================
// BLE SCAN SCREEN (Section 5)
// ============================================================

@SuppressLint("MissingPermission")
@Composable
fun BleScanScreen(
    devices: List<ScanResult>,
    isScanning: Boolean,
    tagBindings: List<TagBinding>,
    onStartScan: () -> Unit,
    onDeviceClick: (String) -> Unit
) {
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(
                Brush.verticalGradient(
                    colors = listOf(GradientDarkGreen, GradientMid, GradientBlack)
                )
            )
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(horizontal = 24.dp)
                .padding(top = 48.dp)
        ) {
            // Title
            Text(
                text = "灵犀 Tag",
                style = MaterialTheme.typography.headlineLarge,
                color = TextOnDark,
                fontWeight = FontWeight.SemiBold
            )

            Spacer(modifier = Modifier.height(6.dp))

            // Subtitle
            Text(
                text = "查找你的物品...",
                style = MaterialTheme.typography.bodyLarge,
                color = TextOnDark.copy(alpha = 0.7f)
            )

            Spacer(modifier = Modifier.height(28.dp))

            // Scan Button
            val buttonScale by animateFloatAsState(
                targetValue = if (isScanning) 0.97f else 1.0f,
                animationSpec = spring(dampingRatio = Spring.DampingRatioMediumBouncy),
                label = "buttonScale"
            )

            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .scale(buttonScale)
                    .shadow(8.dp, RoundedCornerShape(18.dp))
                    .clip(RoundedCornerShape(18.dp))
                    .background(CardDark)
                    .clickable(enabled = !isScanning) { onStartScan() }
                    .padding(vertical = 16.dp),
                contentAlignment = Alignment.Center
            ) {
                if (isScanning) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(22.dp),
                            color = PrimaryGreen,
                            strokeWidth = 2.dp
                        )
                        Spacer(modifier = Modifier.width(12.dp))
                        Text(
                            text = "正在搜索物品...",
                            style = MaterialTheme.typography.labelLarge,
                            color = PrimaryGreen,
                            fontWeight = FontWeight.SemiBold
                        )
                    }
                } else {
                    Text(
                        text = "搜索物品",
                        style = MaterialTheme.typography.labelLarge,
                        color = PrimaryGreen,
                        fontWeight = FontWeight.SemiBold
                    )
                }
            }

            Spacer(modifier = Modifier.height(16.dp))

            // Status text
            Text(
                text = when {
                    isScanning && devices.isEmpty() -> "搜索中..."
                    devices.isNotEmpty() -> "${devices.size} 个设备"
                    else -> "点击扫描搜索附近的标签"
                },
                style = MaterialTheme.typography.labelSmall,
                color = Color.White.copy(alpha = 0.5f)
            )

            Spacer(modifier = Modifier.height(12.dp))

            // Device list or empty state
            if (devices.isEmpty() && !isScanning) {
                EmptyState()
            } else {
                LazyColumn(
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    itemsIndexed(devices) { index, result ->
                        AnimatedVisibility(
                            visible = true,
                            enter = fadeIn(animationSpec = tween(400)) +
                                slideInVertically(
                                    animationSpec = tween(400),
                                    initialOffsetY = { it / 2 }
                                )
                        ) {
                            val existingBinding = tagBindings.find { it.mac == result.device.address }
                            DeviceCard(
                                result = result,
                                index = index,
                                customName = existingBinding?.name,
                                onClick = { onDeviceClick(result.device.address) }
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
fun EmptyState() {
    Box(
        modifier = Modifier.fillMaxSize(),
        contentAlignment = Alignment.Center
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            // Vector radar icon
            Canvas(modifier = Modifier.size(56.dp)) {
                val cx = size.width / 2; val cy = size.height / 2
                val r = size.minDimension * 0.42f; val s = 1.8.dp.toPx()
                // outer ring
                drawCircle(Color.White.copy(alpha = 0.3f), r, Offset(cx, cy), style = Stroke(s))
                // middle ring
                drawCircle(Color.White.copy(alpha = 0.2f), r * 0.62f, Offset(cx, cy), style = Stroke(s * 0.7f))
                // center dot
                drawCircle(Color.White.copy(alpha = 0.5f), s * 1.5f, Offset(cx, cy))
                // scan line (45°)
                val endX = cx + r * 0.85f * kotlin.math.cos(Math.toRadians(-45.0)).toFloat()
                val endY = cy + r * 0.85f * kotlin.math.sin(Math.toRadians(-45.0)).toFloat()
                drawLine(Color.White.copy(alpha = 0.4f), Offset(cx, cy), Offset(endX, endY), s * 0.8f, StrokeCap.Round)
            }
            Spacer(modifier = Modifier.height(12.dp))
            Text(
                text = "未发现设备",
                style = MaterialTheme.typography.bodyLarge,
                color = Color.White.copy(alpha = 0.5f)
            )
            Text(
                text = "点击扫描搜索附近的标签",
                style = MaterialTheme.typography.bodySmall,
                color = Color.White.copy(alpha = 0.35f)
            )
        }
    }
}

@SuppressLint("MissingPermission")
@Composable
fun DeviceCard(
    result: ScanResult,
    index: Int,
    customName: String?,
    onClick: () -> Unit
) {
    val rssi = result.rssi
    val name = customName ?: result.scanRecord?.deviceName ?: result.device.name ?: "Unknown Device"
    val mac = result.device.address
    val isBound = customName != null
    val signalColor = when {
        rssi > -60 -> SignalStrong
        rssi > -75 -> SignalMedium
        else -> SignalWeak
    }

    Card(
        modifier = Modifier
            .fillMaxWidth()
            .shadow(2.dp, RoundedCornerShape(16.dp))
            .clickable { onClick() },
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(containerColor = CardGlass)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            // Signal dot
            Box(
                modifier = Modifier
                    .size(12.dp)
                    .clip(CircleShape)
                    .background(signalColor)
            )

            Spacer(modifier = Modifier.width(14.dp))

            // Name
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = name,
                    style = MaterialTheme.typography.titleMedium,
                    color = if (isBound) PrimaryGreen else TextPrimary,
                    fontWeight = FontWeight.SemiBold
                )
                if (isBound) {
                    Text(
                        text = "已绑定",
                        style = MaterialTheme.typography.bodySmall,
                        color = PrimaryGreen.copy(alpha = 0.6f)
                    )
                }
            }

            // RSSI + Signal bars
            Column(horizontalAlignment = Alignment.End) {
                Text(
                    text = "$rssi dBm",
                    style = MaterialTheme.typography.labelSmall,
                    color = signalColor,
                    fontWeight = FontWeight.Medium
                )
                Spacer(modifier = Modifier.height(4.dp))
                SignalBars(rssi = rssi)
            }
        }
    }
}

@Composable
fun SignalBars(rssi: Int) {
    val activeBars = when {
        rssi > -55 -> 4
        rssi > -65 -> 3
        rssi > -75 -> 2
        rssi > -85 -> 1
        else -> 0
    }
    val barColor = when {
        rssi > -65 -> SignalStrong
        rssi > -75 -> SignalMedium
        else -> SignalWeak
    }
    val inactiveColor = Color(0xFF2A332F)

    Canvas(modifier = Modifier.width(22.dp).height(21.dp)) {
        val barWidth = 4.dp.toPx()
        val gap = 2.dp.toPx()
        val heights = listOf(6.dp.toPx(), 11.dp.toPx(), 16.dp.toPx(), 21.dp.toPx())
        val totalW = 4 * barWidth + 3 * gap
        val startX = (size.width - totalW) / 2

        heights.forEachIndexed { i, h ->
            val x = startX + i * (barWidth + gap)
            val y = size.height - h
            drawRoundRect(
                color = if (i < activeBars) barColor else inactiveColor,
                topLeft = Offset(x, y),
                size = Size(barWidth, h),
                cornerRadius = androidx.compose.ui.geometry.CornerRadius(2.dp.toPx())
            )
        }
    }
}

// ============================================================
// HALO TAG RADAR SCREEN (Section 6)
// ============================================================

@Composable
fun HaloTagRadarScreen(
    connState: String,
    connectedMac: String,
    uwbData: UwbRealData,
    findingStatus: FindingStatus,
    debugLogs: List<String>,
    tagBindings: List<TagBinding>,
    onStartFindingClick: () -> Unit,
    onDisconnect: () -> Unit,
    onConnectSavedDevice: (String) -> Unit,
    onAddBinding: (mac: String, name: String) -> Unit
) {
    val isSuccess = findingStatus == FindingStatus.SUCCESS
    val isNearby = isSuccess && uwbData.distanceMeters <= 1.0f
    val isSearching = findingStatus in setOf(
        FindingStatus.SENDING, FindingStatus.BOARD_STARTING,
        FindingStatus.BOARD_STARTED, FindingStatus.RANGING
    )
    val isError = findingStatus in setOf(FindingStatus.FAILED, FindingStatus.TIMEOUT, FindingStatus.UWB_ERROR)

    // Precision finding alignment states
    val isAligned = isSuccess && abs(uwbData.azimuthDegrees) <= 15f
    val isGuided = isSuccess && !isAligned

    val isFinding = findingStatus == FindingStatus.SENDING

    // Animated background color based on finding state
    val backgroundColor by animateColorAsState(
        targetValue = when {
            isAligned -> AlignedGreenBg
            isGuided -> GuidedBgBlack
            isSearching -> SearchBgDark
            isError -> BackgroundBlack
            else -> BackgroundBlack
        },
        animationSpec = tween(700, easing = EaseInOutCubic),
        label = "bgColor"
    )

    // Secondary button scale animations
    val playScale by animateFloatAsState(
        targetValue = if (isFinding) 0.97f else 1.0f,
        animationSpec = spring(dampingRatio = Spring.DampingRatioMediumBouncy),
        label = "playScale"
    )
    val dirScale by animateFloatAsState(
        targetValue = if (isFinding) 0.97f else 1.0f,
        animationSpec = spring(dampingRatio = Spring.DampingRatioMediumBouncy),
        label = "dirScale"
    )

    // Auto-dialog for first-time binding
    val existingBinding = tagBindings.find { it.mac == connectedMac }
    var showBindDialog by remember { mutableStateOf(false) }
    var bindName by remember { mutableStateOf("") }

    // Show dialog automatically if this MAC is not yet bound
    LaunchedEffect(connectedMac) {
        if (connectedMac.isNotEmpty() && existingBinding == null) {
            showBindDialog = true
        }
    }

    // Bind dialog
    if (showBindDialog) {
        AlertDialog(
            onDismissRequest = { showBindDialog = false },
            containerColor = CardDark,
            title = {
                Text("发现新标签！", color = PrimaryGreen, fontWeight = FontWeight.SemiBold)
            },
            text = {
                Column {
                    Text(
                        text = "这个标签贴在什么物品上？",
                        style = MaterialTheme.typography.bodyMedium,
                        color = TextSecondary
                    )
                    Spacer(modifier = Modifier.height(12.dp))
                    OutlinedTextField(
                        value = bindName,
                        onValueChange = { bindName = it },
                        label = { Text("物品名称", color = TextSecondary) },
                        placeholder = { Text("例如：钥匙、钱包...", color = TextMuted) },
                        singleLine = true,
                        colors = OutlinedTextFieldDefaults.colors(
                            focusedTextColor = TextPrimary,
                            unfocusedTextColor = TextPrimary,
                            focusedBorderColor = PrimaryGreen,
                            unfocusedBorderColor = TextMuted
                        ),
                        modifier = Modifier.fillMaxWidth()
                    )
                }
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        val name = bindName.trim().ifEmpty { "My Tag" }
                        onAddBinding(connectedMac, name)
                        showBindDialog = false
                        bindName = ""
                    }
                ) {
                    Text("保存", color = PrimaryGreen, fontWeight = FontWeight.SemiBold)
                }
            },
            dismissButton = {
                TextButton(onClick = { showBindDialog = false; bindName = "" }) {
                    Text("跳过", color = TextSecondary)
                }
            }
        )
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(backgroundColor)
    ) {
        Column(
            modifier = Modifier.fillMaxSize()
        ) {
            // ---- Scrollable Content ----
            LazyColumn(
                modifier = Modifier.fillMaxSize(),
                horizontalAlignment = Alignment.CenterHorizontally,
                contentPadding = PaddingValues(vertical = 16.dp)
            ) {
                // Precision Finding Display
                item {
                    Box(
                        modifier = Modifier
                            .size(300.dp)
                            .padding(top = 8.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        PrecisionFindingDisplay(
                            isSearching = isSearching,
                            isGuided = isGuided,
                            isAligned = isAligned,
                            azimuthDegrees = uwbData.azimuthDegrees,
                            elevationDegrees = uwbData.elevationDegrees,
                            distanceMeters = uwbData.distanceMeters,
                            modifier = Modifier.fillMaxSize()
                        )
                    }

                    Spacer(modifier = Modifier.height(20.dp))

                    // Status label
                    val statusLabel = when {
                        isAligned -> "已对准"
                        isGuided -> "导航中"
                        isSearching -> "搜索中..."
                        isError -> "错误"
                        else -> "就绪"
                    }
                    val statusColor = when {
                        isAligned -> Color.White
                        isGuided -> PrimaryGreen
                        isSearching -> PrimaryGreen
                        isError -> StatusLost
                        else -> TextMuted
                    }
                    Text(
                        text = statusLabel,
                        style = MaterialTheme.typography.labelMedium,
                        color = statusColor,
                        letterSpacing = 3.sp
                    )

                    Spacer(modifier = Modifier.height(16.dp))
                }

                // Distance and Direction Display
                if (isSuccess) {
                    item {
                        PrecisionDataDisplay(
                            uwbData = uwbData,
                            isNearby = isNearby,
                            isAligned = isAligned
                        )
                        Spacer(modifier = Modifier.height(12.dp))
                    }
                    item {
                        RangingMetricsRow(
                            uwbData = uwbData,
                            isActive = isSuccess
                        )
                        Spacer(modifier = Modifier.height(20.dp))
                    }
                }

                // (InfoCard removed)

                // Action Buttons (Play Sound + Direction — secondary)
                item {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 24.dp),
                        horizontalArrangement = Arrangement.spacedBy(12.dp)
                    ) {
                        // Play Sound
                        Box(
                            modifier = Modifier
                                .weight(1f)
                                .scale(playScale)
                                .shadow(6.dp, RoundedCornerShape(18.dp))
                                .clip(RoundedCornerShape(18.dp))
                                .background(
                                    brush = Brush.horizontalGradient(
                                        colors = listOf(ButtonDarkStart, ButtonDarkEnd)
                                    )
                                )
                                .clickable(enabled = !isFinding) { onStartFindingClick() }
                                .height(56.dp),
                            contentAlignment = Alignment.Center
                        ) {
                            Text(
                                text = "播放声音",
                                style = MaterialTheme.typography.labelLarge,
                                color = Color.White
                            )
                        }
                        // Direction
                        Box(
                            modifier = Modifier
                                .weight(1f)
                                .scale(dirScale)
                                .shadow(6.dp, RoundedCornerShape(18.dp))
                                .clip(RoundedCornerShape(18.dp))
                                .background(
                                    brush = Brush.horizontalGradient(
                                        colors = listOf(ButtonDarkStart, ButtonDarkEnd)
                                    )
                                )
                                .clickable(enabled = !isFinding) { onStartFindingClick() }
                                .height(56.dp),
                            contentAlignment = Alignment.Center
                        ) {
                            Text(
                                text = "方向指引",
                                style = MaterialTheme.typography.labelLarge,
                                color = Color.White
                            )
                        }
                    }
                    Spacer(modifier = Modifier.height(16.dp))
                }

                // ---- MAIN ACTION BUTTON (state-dependent, primary CTA) ----
                item {
                    val isSuccessState = findingStatus == FindingStatus.SUCCESS
                    val mainBtnColor = when (findingStatus) {
                        FindingStatus.SUCCESS -> PrimaryGreen
                        FindingStatus.FAILED, FindingStatus.TIMEOUT, FindingStatus.UWB_ERROR -> StatusLost
                        FindingStatus.BOARD_STARTED -> Color(0xFF607D8B)
                        else -> PrimaryGreen
                    }
                    val mainBtnText = when (findingStatus) {
                        FindingStatus.IDLE -> "开始查找"
                        FindingStatus.SENDING -> "等待 OOB 确认..."
                        FindingStatus.BOARD_STARTING -> "等待板端启动..."
                        FindingStatus.BOARD_STARTED -> "板端就绪：重新开始"
                        FindingStatus.RANGING -> "等待 UWB 测距结果..."
                        FindingStatus.SUCCESS -> String.format("找到了！(%.1f米)", uwbData.distanceMeters)
                        FindingStatus.FAILED -> "写入失败：重试"
                        FindingStatus.TIMEOUT -> "确认超时：重试"
                        FindingStatus.UWB_ERROR -> "测距失败：重试"
                    }

                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 24.dp)
                            .shadow(8.dp, RoundedCornerShape(14.dp))
                            .clip(RoundedCornerShape(14.dp))
                            .background(
                                if (isFinding) SurfaceDark else mainBtnColor
                            )
                            .clickable(enabled = !isFinding) {
                                if (isSuccessState) onDisconnect() else onStartFindingClick()
                            }
                            .height(56.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        Text(
                            text = mainBtnText,
                            style = MaterialTheme.typography.labelLarge,
                            color = Color.White,
                            fontWeight = FontWeight.SemiBold
                        )
                    }
                    Spacer(modifier = Modifier.height(20.dp))
                }

                // MY DEVICES section
                item {
                    Text(
                        text = "我的设备",
                        style = MaterialTheme.typography.labelSmall,
                        color = TextMuted,
                        letterSpacing = 1.5.sp,
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 24.dp)
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                }

                if (tagBindings.isEmpty()) {
                    item {
                        Text(
                            text = "暂无已保存的标签，扫描后点击＋绑定",
                            style = MaterialTheme.typography.bodySmall,
                            color = TextMuted,
                            modifier = Modifier.padding(horizontal = 24.dp)
                        )
                    }
                } else {
                    for (binding in tagBindings) {
                        val isConnected = binding.mac == connectedMac
                        item {
                            MyDeviceItem(
                                name = binding.name,
                                isCurrent = isConnected,
                                status = "",
                                onClick = {
                                    if (!isConnected) onConnectSavedDevice(binding.mac)
                                }
                            )
                        }
                    }
                }

                item { Spacer(modifier = Modifier.height(32.dp)) }
            }
        }
    }
}

// ---- Top Bar (Section 6.3) ----

@Composable
fun TopBar(connState: String, onDisconnect: () -> Unit) {
    val isConnected = connState == "已连接"
    val dotColor = when {
        isConnected -> StatusDotGreen
        connState.contains("连接") -> StatusDotOrange
        else -> StatusDotRed
    }

    Box(
        modifier = Modifier
            .fillMaxWidth()
            .background(
                Brush.verticalGradient(
                    colors = listOf(GradientDarkGreen, GradientBlack)
                )
            )
            .padding(top = 48.dp)
            .padding(horizontal = 20.dp, vertical = 14.dp)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically
        ) {
            // Connection indicator
            Box(
                modifier = Modifier
                    .size(8.dp)
                    .clip(CircleShape)
                    .background(dotColor)
            )

            Spacer(modifier = Modifier.width(10.dp))

            Column {
                Text(
                    text = "灵犀Tag",
                    style = MaterialTheme.typography.titleSmall,
                    color = Color.White.copy(alpha = 0.7f)
                )
                Text(
                    text = connState,
                    style = MaterialTheme.typography.labelMedium,
                    color = Color.White,
                    fontWeight = FontWeight.SemiBold
                )
            }

            Spacer(modifier = Modifier.weight(1f))

            TextButton(onClick = onDisconnect) {
                Text(
                    text = "断开连接",
                    style = MaterialTheme.typography.labelMedium,
                    color = Color.White.copy(alpha = 0.7f)
                )
            }
        }
    }
}

// ============================================================
// PRECISION FINDING DISPLAY (Section 6.4 - Redesigned)
// Three states: Searching (particles), Guided (arc + arrow), Aligned (green)
// ============================================================

@Composable
fun PrecisionFindingDisplay(
    isSearching: Boolean,
    isGuided: Boolean,
    isAligned: Boolean,
    azimuthDegrees: Float,
    elevationDegrees: Float = 0f,
    distanceMeters: Float = Float.MAX_VALUE,
    modifier: Modifier = Modifier
) {
    val infiniteTransition = rememberInfiniteTransition(label = "precisionFinder")

    // Continuous elapsed time for particle orbital motion
    var elapsedTime by remember { mutableStateOf(0f) }
    LaunchedEffect(Unit) {
        var lastNanos = 0L
        while (true) {
            withFrameNanos { nanos ->
                if (lastNanos != 0L) {
                    val dt = (nanos - lastNanos) / 1_000_000_000f
                    elapsedTime += dt
                }
                lastNanos = nanos
            }
        }
    }

    // Transition: particles → arc (0 = full particles, 1 = full arc)
    val guidedTransition by animateFloatAsState(
        targetValue = if (isGuided || isAligned) 1f else 0f,
        animationSpec = tween(650, easing = EaseInOutCubic),
        label = "guidedTransition"
    )

    // Breathing for aligned state
    val breatheScale by infiniteTransition.animateFloat(
        initialValue = 1f,
        targetValue = 1.07f,
        animationSpec = infiniteRepeatable(
            animation = tween(1500, easing = EaseInOutCubic),
            repeatMode = RepeatMode.Reverse
        ),
        label = "breathe"
    )

    // Green glow ripple for aligned state
    val rippleRadius by infiniteTransition.animateFloat(
        initialValue = 0.35f,
        targetValue = 0.95f,
        animationSpec = infiniteRepeatable(
            animation = tween(1200, easing = EaseOutCubic),
            repeatMode = RepeatMode.Restart
        ),
        label = "ripple"
    )
    val rippleAlpha = (0.5f * (1f - rippleRadius)).coerceIn(0f, 0.5f)

    // ---- Alarm-clock shake when within 0.3m ----
    val isShaking = (isGuided || isAligned) && distanceMeters < 0.3f
    val shakeIntensity by animateFloatAsState(
        targetValue = if (isShaking) {
            ((0.3f - distanceMeters) / 0.3f).coerceIn(0f, 1f)
        } else 0f,
        animationSpec = tween(200, easing = EaseOutCubic),
        label = "shakeIntensity"
    )
    val shakePhase by infiniteTransition.animateFloat(
        initialValue = 0f,
        targetValue = 2f * PI.toFloat(),
        animationSpec = infiniteRepeatable(
            animation = tween(140, easing = LinearEasing),
            repeatMode = RepeatMode.Restart
        ),
        label = "shakePhase"
    )
    val localDensity = LocalDensity.current
    val maxShakePx = with(localDensity) { 5.dp.toPx() } * shakeIntensity
    val shakeTranslationX = sin(shakePhase.toDouble()).toFloat() * maxShakePx
    val shakeRotationZ = sin(shakePhase.toDouble() * 2.0).toFloat() * shakeIntensity * 2f

    // Smooth arrow rotation
    val animatedAngle by animateFloatAsState(
        targetValue = when {
            isAligned -> 0f
            isGuided -> azimuthDegrees
            else -> 0f
        },
        animationSpec = spring(
            dampingRatio = Spring.DampingRatioLowBouncy,
            stiffness = Spring.StiffnessLow
        ),
        label = "arrowAngle"
    )

    // Generate particles once
    val particles = remember { generateFinderParticles(55, 0.82f) }

    Box(
        modifier = modifier.graphicsLayer {
            translationX = shakeTranslationX
            rotationZ = shakeRotationZ
        },
        contentAlignment = Alignment.Center
    ) {
        // Background canvas layer
        Canvas(modifier = Modifier.fillMaxSize()) {
            val center = Offset(size.width / 2, size.height / 2)
            val radius = size.minDimension / 2 - 4.dp.toPx()

            when {
                isAligned -> {
                    // Green glow background circle
                    drawCircle(
                        color = AlignedGreenBg.copy(alpha = 0.2f),
                        radius = radius,
                        center = center
                    )
                    // Outer ring
                    drawCircle(
                        color = AlignedGreenBg.copy(alpha = 0.5f),
                        radius = radius,
                        center = center,
                        style = Stroke(width = 1.5.dp.toPx())
                    )
                    // Ripple effect
                    drawCircle(
                        color = Color.White.copy(alpha = rippleAlpha * 0.5f),
                        radius = radius * rippleRadius,
                        center = center,
                        style = Stroke(width = 2.dp.toPx())
                    )
                    // Second ripple (offset phase)
                    val ripple2Radius = ((rippleRadius + 0.5f) % 1f).coerceIn(0f, 1f)
                    val ripple2Alpha = (0.5f * (1f - ripple2Radius)).coerceIn(0f, 0.5f) * 0.6f
                    drawCircle(
                        color = Color.White.copy(alpha = ripple2Alpha),
                        radius = radius * ripple2Radius,
                        center = center,
                        style = Stroke(width = 1.5.dp.toPx())
                    )
                }
                isGuided -> {
                    // Dark background for guided state
                    drawCircle(
                        color = Color(0xFF0D1110),
                        radius = radius,
                        center = center
                    )
                    // Thin outer ring
                    drawCircle(
                        color = Color(0xFF1A3328),
                        radius = radius,
                        center = center,
                        style = Stroke(width = 1.2.dp.toPx())
                    )

                    val arcStartAngle = -90f
                    val sweepAngle = azimuthDegrees

                    if (abs(sweepAngle) > 0.5f) {
                        val arcEndAngle = arcStartAngle + sweepAngle
                        val arcStartRad = Math.toRadians(arcStartAngle.toDouble()).toFloat()
                        val arcEndRad = Math.toRadians(arcEndAngle.toDouble()).toFloat()

                        // Wide soft glow arc underneath
                        drawArc(
                            brush = Brush.sweepGradient(
                                0.0f to Color.White.copy(alpha = 0.3f),
                                0.3f to Color.White.copy(alpha = 0.15f),
                                1.0f to Color.Transparent
                            ),
                            startAngle = arcStartAngle,
                            sweepAngle = sweepAngle,
                            useCenter = false,
                            topLeft = Offset(center.x - radius, center.y - radius),
                            size = Size(radius * 2, radius * 2),
                            style = Stroke(width = 10.dp.toPx(), cap = StrokeCap.Round)
                        )

                        // Solid arc line (fades in as particles converge)
                        val arcAlpha = guidedTransition.coerceIn(0f, 1f)
                        drawArc(
                            color = Color.White.copy(alpha = arcAlpha),
                            startAngle = arcStartAngle,
                            sweepAngle = sweepAngle,
                            useCenter = false,
                            topLeft = Offset(center.x - radius, center.y - radius),
                            size = Size(radius * 2, radius * 2),
                            style = Stroke(width = 3.dp.toPx(), cap = StrokeCap.Round)
                        )

                        // ---- Thick endpoint dots ----
                        if (arcAlpha > 0.5f) {
                            // Start point (top / user heading) — smaller
                            val startX = center.x + cos(arcStartRad) * radius
                            val startY = center.y + sin(arcStartRad) * radius
                            drawCircle(
                                color = Color.White.copy(alpha = arcAlpha * 0.7f),
                                radius = 4.5.dp.toPx(),
                                center = Offset(startX, startY)
                            )
                            drawCircle(
                                color = Color.White.copy(alpha = arcAlpha),
                                radius = 3.5.dp.toPx(),
                                center = Offset(startX, startY)
                            )

                            // End point (tag direction) — larger & bolder
                            val endX = center.x + cos(arcEndRad) * radius
                            val endY = center.y + sin(arcEndRad) * radius
                            drawCircle(
                                color = Color.White.copy(alpha = arcAlpha * 0.8f),
                                radius = 7.dp.toPx(),
                                center = Offset(endX, endY)
                            )
                            drawCircle(
                                color = Color.White.copy(alpha = arcAlpha),
                                radius = 5.5.dp.toPx(),
                                center = Offset(endX, endY)
                            )
                        }
                    }

                    // Converging particles — fade out as solid arc takes over
                    val particleFade = (1f - guidedTransition * 1.15f).coerceIn(0f, 1f)
                    if (particleFade > 0.03f) {
                        val arcBaseRad = Math.toRadians(arcStartAngle.toDouble()).toFloat()
                        val azimuthRad = Math.toRadians(azimuthDegrees.toDouble()).toFloat()
                        particles.forEach { p ->
                            // Orbital position
                            val orbitAngle = p.angle + elapsedTime * p.speed * p.direction * 0.5f
                            val orbitX = center.x + cos(orbitAngle) * radius * p.orbitRadius
                            val orbitY = center.y + sin(orbitAngle) * radius * p.orbitRadius
                            // Arc target
                            val arcAngle = arcBaseRad + azimuthRad * p.arcFraction
                            val arcX = center.x + cos(arcAngle) * radius * p.orbitRadius
                            val arcY = center.y + sin(arcAngle) * radius * p.orbitRadius
                            // Interpolate
                            val px = orbitX + (arcX - orbitX) * guidedTransition
                            val py = orbitY + (arcY - orbitY) * guidedTransition
                            // Fade
                            val alpha = particleFade * ((sin(elapsedTime * 2.5f + p.phase).toFloat() + 1f) / 4f + 0.15f)
                            if (alpha > 0.03f) {
                                drawCircle(
                                    color = ParticleGlow.copy(alpha = alpha),
                                    radius = p.size.dp.toPx(),
                                    center = Offset(px, py)
                                )
                            }
                        }
                    }
                }
                isSearching -> {
                    // Semi-dark circular area
                    drawCircle(
                        color = SearchBgDark.copy(alpha = 0.9f),
                        radius = radius,
                        center = center
                    )
                    // Faint outer ring
                    drawCircle(
                        color = Color.White.copy(alpha = 0.08f),
                        radius = radius,
                        center = center,
                        style = Stroke(width = 1.dp.toPx())
                    )

                    // Draw orbiting particles along the circle edge
                    particles.forEach { p ->
                        // Slow orbital motion with random direction (CW or CCW)
                        val currentAngle = p.angle + elapsedTime * p.speed * p.direction * 0.5f
                        val px = center.x + cos(currentAngle) * radius * p.orbitRadius
                        val py = center.y + sin(currentAngle) * radius * p.orbitRadius
                        // Gentle breathing blink
                        val alpha = (
                            (sin(elapsedTime * 2.0f + p.phase).toFloat() + 1f) / 4f + 0.15f
                        )
                        if (alpha > 0.04f) {
                            drawCircle(
                                color = ParticleGlow.copy(alpha = alpha),
                                radius = p.size.dp.toPx(),
                                center = Offset(px, py)
                            )
                        }
                    }

                    // Subtle center glow
                    drawCircle(
                        color = PrimaryGreen.copy(alpha = 0.04f),
                        radius = radius * 0.12f,
                        center = center
                    )
                }
                else -> {
                    // Idle / error state
                    drawCircle(
                        color = Color(0xFF0F1A15),
                        radius = radius,
                        center = center
                    )
                    drawCircle(
                        color = Color(0xFF1A3328),
                        radius = radius,
                        center = center,
                        style = Stroke(width = 1.dp.toPx())
                    )
                }
            }
        }

        // 3D Perspective Arrow overlay (guided or aligned state)
        if (isGuided || isAligned) {
            // Floating idle animation
            val floatOffset by infiniteTransition.animateFloat(
                initialValue = -2.5f,
                targetValue = 2.5f,
                animationSpec = infiniteRepeatable(
                    animation = tween(2200, easing = EaseInOutCubic),
                    repeatMode = RepeatMode.Reverse
                ),
                label = "arrowFloat"
            )

            // Smoothed elevation for 3D tilt
            val animatedElevation by animateFloatAsState(
                targetValue = if (isGuided && elevationDegrees != 0f) {
                    (elevationDegrees * 0.65f).coerceIn(-28f, 28f)
                } else 0f,
                animationSpec = spring(
                    dampingRatio = Spring.DampingRatioMediumBouncy,
                    stiffness = Spring.StiffnessLow
                ),
                label = "arrowElevation"
            )

            val heightColor = when {
                elevationDegrees == 0f -> Color(0xFFECEFF1)
                elevationDegrees > 8f -> Color(0xFF40C4FF)
                elevationDegrees < -8f -> Color(0xFFFFAB40)
                else -> Color.White
            }

            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .graphicsLayer {
                        cameraDistance = 9f * density
                        rotationZ = animatedAngle
                        rotationX = -animatedElevation * 0.85f
                        rotationY = animatedElevation * 0.18f
                        translationY = -animatedElevation * density * 0.22f
                        shadowElevation = 18f
                        scaleX = 1f + abs(animatedElevation) / 360f
                        scaleY = 1f - abs(animatedElevation) / 620f
                    }
                    .offset(y = floatOffset.dp),
                contentAlignment = Alignment.Center
            ) {
                PrecisionArrow(
                    modifier = Modifier.size(90.dp),
                    isAligned = isAligned,
                    accentColor = heightColor
                )
            }
        }

        // (Searching spinner removed — particles alone indicate searching)
    }
}

// ============================================================
// PRECISION ARROW (Section 6.6 - Redesigned)
// 3D perspective V-shaped dart arrow with full rounded corners
// ============================================================

@Composable
fun PrecisionArrow(modifier: Modifier = Modifier, isAligned: Boolean = false, accentColor: Color = Color.White) {
    val bottomColor = if (isAligned) Color.White else Color(0xFFECEFF1)
    Canvas(modifier = modifier) {
        val shadow = Path().apply {
            moveTo(size.width / 2, size.height * 0.07f)
            lineTo(size.width * 0.93f, size.height * 0.88f)
            lineTo(size.width * 0.5f, size.height * 0.69f)
            lineTo(size.width * 0.07f, size.height * 0.88f)
            close()
        }
        val path = Path().apply {
            moveTo(size.width / 2, 0f)
            lineTo(size.width, size.height * 0.85f)
            lineTo(size.width * 0.5f, size.height * 0.65f)
            lineTo(0f, size.height * 0.85f)
            close()
        }
        drawPath(path = shadow, color = Color(0x66000000))
        drawPath(
            path = path,
            brush = Brush.verticalGradient(colors = listOf(Color.White, accentColor, bottomColor))
        )
    }
}

// ---- Precision Data Display ----

@Composable
fun PrecisionDataDisplay(uwbData: UwbRealData, isNearby: Boolean, isAligned: Boolean) {
    val distanceColor = when {
        isAligned -> Color.White
        isNearby -> PrimaryGreen
        else -> TextPrimary
    }
    val directionLabel = azimuthToRelativeDirection(uwbData.azimuthDegrees)

    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        // Distance row (large, prominent)
        Row(verticalAlignment = Alignment.Bottom) {
            Text(
                text = String.format("%.1f", uwbData.distanceMeters),
                style = MaterialTheme.typography.displayLarge,
                color = distanceColor
            )
            Text(
                text = "米",
                style = MaterialTheme.typography.headlineSmall,
                color = distanceColor,
                modifier = Modifier.padding(bottom = 8.dp)
            )
        }

        Spacer(modifier = Modifier.height(6.dp))

        // Direction label (relative: front/left/right/behind)
        Text(
            text = directionLabel,
            style = MaterialTheme.typography.titleMedium,
            color = if (isAligned) Color.White else PrimaryGreen,
            fontWeight = FontWeight.SemiBold
        )

        Spacer(modifier = Modifier.height(4.dp))

        // Angle display (small, secondary)
        Text(
            text = "${uwbData.azimuthDegrees.toInt()}°",
            style = MaterialTheme.typography.labelSmall,
            color = if (isAligned) Color.White.copy(alpha = 0.7f) else TextMuted,
            fontWeight = FontWeight.Medium
        )
    }
}

@Composable
fun RangingMetricsRow(uwbData: UwbRealData, isActive: Boolean) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 24.dp)
            .padding(top = 2.dp),
        horizontalArrangement = Arrangement.SpaceEvenly,
        verticalAlignment = Alignment.CenterVertically
    ) {
        RangingMetric(
            value = if (isActive) String.format("%.2fm", uwbData.horizontalDistanceMeters) else "-",
            label = "水平距离",
            color = Color(0xFF40C4FF)
        )
        RangingMetric(
            value = if (isActive) String.format("%.1f°", uwbData.azimuthDegrees) else "-",
            label = "方位角",
            color = Color(0xFF00E676)
        )
        RangingMetric(
            value = if (isActive && uwbData.hasElevation) String.format("%+.2fm", uwbData.relativeHeightMeters) else "-",
            label = "高度差",
            color = when {
                !isActive || !uwbData.hasElevation -> Color(0xFFB0BEC5)
                uwbData.relativeHeightMeters > 0.08f -> Color(0xFF40C4FF)
                uwbData.relativeHeightMeters < -0.08f -> Color(0xFFFFAB40)
                else -> Color.White
            }
        )
    }
}

@Composable
fun RangingMetric(value: String, label: String, color: Color) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Text(
            text = value,
            color = color,
            fontSize = 20.sp,
            fontWeight = FontWeight.Bold
        )
        Text(
            text = label,
            color = Color(0xFF8A948F),
            fontSize = 12.sp
        )
    }
}

fun azimuthToRelativeDirection(degrees: Float): String {
    val absDeg = abs(degrees)
    return when {
        absDeg <= 15f -> "前方"
        degrees > 15f && degrees < 165f -> "右侧"
        degrees < -15f && degrees > -165f -> "左侧"
        absDeg >= 165f -> "后方"
        else -> "前方"
    }
}

// ---- Legacy Data Display (kept for reference) ----

@Composable
fun DataDisplay(uwbData: UwbRealData, isNearby: Boolean) {
    val distanceColor = if (isNearby) PrimaryGreen else TextPrimary
    val directionLabel = azimuthToRelativeDirection(uwbData.azimuthDegrees)

    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Row(verticalAlignment = Alignment.Bottom) {
            Text(
                text = String.format("%.1f", uwbData.distanceMeters),
                style = MaterialTheme.typography.displayLarge,
                color = distanceColor
            )
            Text(
                text = " m",
                style = MaterialTheme.typography.headlineSmall,
                color = distanceColor,
                modifier = Modifier.padding(bottom = 8.dp)
            )
        }

        Spacer(modifier = Modifier.height(4.dp))

        Row(verticalAlignment = Alignment.Bottom) {
            Text(
                text = "${uwbData.azimuthDegrees.toInt()}",
                style = MaterialTheme.typography.displaySmall,
                color = PrimaryGreen
            )
            Text(
                text = "°",
                style = MaterialTheme.typography.headlineSmall,
                color = PrimaryGreen,
                modifier = Modifier.padding(bottom = 4.dp)
            )
        }

        Spacer(modifier = Modifier.height(2.dp))

        Text(
            text = directionLabel,
            style = MaterialTheme.typography.labelSmall,
            color = TextMuted,
            fontWeight = FontWeight.Medium
        )
    }
}

fun azimuthToDirection(degrees: Float): String {
    val deg = ((degrees % 360) + 360) % 360
    return when {
        deg >= 337.5f || deg < 22.5f -> "N ↑"
        deg >= 22.5f && deg < 67.5f -> "NE ↗"
        deg >= 67.5f && deg < 112.5f -> "E →"
        deg >= 112.5f && deg < 157.5f -> "SE ↘"
        deg >= 157.5f && deg < 202.5f -> "S ↓"
        deg >= 202.5f && deg < 247.5f -> "SW ↙"
        deg >= 247.5f && deg < 292.5f -> "W ←"
        else -> "NW ↖"
    }
}

// ---- InfoCard (Section 6.7) ----

@Composable
fun InfoCard(
    deviceName: String,
    uwbData: UwbRealData,
    findingStatus: FindingStatus
) {
    val statusText = when (findingStatus) {
        FindingStatus.IDLE -> "准备就绪"
        FindingStatus.SENDING, FindingStatus.BOARD_STARTING -> "正在唤醒基站..."
        FindingStatus.BOARD_STARTED, FindingStatus.RANGING -> "正在启动测距..."
        FindingStatus.SUCCESS -> "近距离 (约 ${String.format("%.1f", uwbData.distanceMeters)} 米)"
        FindingStatus.FAILED -> "写入失败 — 请重试"
        FindingStatus.TIMEOUT -> "标签无响应"
        FindingStatus.UWB_ERROR -> "UWB 测距失败"
    }
    val statusColor = when {
        findingStatus == FindingStatus.SUCCESS && uwbData.distanceMeters <= 1.0f -> PrimaryGreen
        findingStatus == FindingStatus.SUCCESS -> StatusFar
        findingStatus in setOf(FindingStatus.FAILED, FindingStatus.TIMEOUT, FindingStatus.UWB_ERROR) -> StatusLost
        else -> TextSecondary
    }

    Card(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 24.dp)
            .shadow(3.dp, RoundedCornerShape(20.dp)),
        shape = RoundedCornerShape(20.dp),
        colors = CardDefaults.cardColors(containerColor = CardGlass)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(18.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            // Icon
            Box(
                modifier = Modifier
                    .size(44.dp)
                    .clip(CircleShape)
                    .background(Color(0xFF0F1A15)),
                contentAlignment = Alignment.Center
            ) {
                ItemIconVector(
                    category = itemCategory(deviceName),
                    size = 24f,
                    color = Color.White.copy(alpha = 0.8f)
                )
            }

            Spacer(modifier = Modifier.width(14.dp))

            Column {
                Text(
                    text = deviceName,
                    style = MaterialTheme.typography.titleMedium,
                    color = TextPrimary,
                    fontWeight = FontWeight.SemiBold
                )
                Spacer(modifier = Modifier.height(4.dp))
                Text(
                    text = "状态：$statusText",
                    style = MaterialTheme.typography.bodySmall,
                    color = statusColor,
                    fontWeight = FontWeight.Medium
                )
                Text(
                    text = "最近连接：家中",
                    style = MaterialTheme.typography.bodySmall,
                    color = TextMuted
                )
            }
        }
    }
}

// ---- Debug Log Panel ----

@Composable
fun DebugLogPanel(logs: List<String>) {
    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 24.dp)
            .height(150.dp),
        color = SurfaceDark,
        shape = RoundedCornerShape(8.dp)
    ) {
        LazyColumn(
            modifier = Modifier.padding(10.dp),
            reverseLayout = false
        ) {
            items(logs) { line ->
                Text(
                    text = line,
                    color = Color(0xFFC8D6D0),
                    fontSize = 11.sp,
                    lineHeight = 14.sp
                )
            }
        }
    }
}

// ---- MyDeviceItem (Section 6.9) ----

enum class ItemCategory {
    KEY, WALLET, BACKPACK, CAR, PHONE, WATCH, GLASSES,
    UMBRELLA, BIKE, LUGGAGE, PET, HEADPHONE, BOOK, REMOTE, TAG
}

fun itemCategory(name: String): ItemCategory {
    val lower = name.lowercase()
    return when {
        "key" in lower || "钥匙" in lower -> ItemCategory.KEY
        "wallet" in lower || "钱包" in lower -> ItemCategory.WALLET
        "backpack" in lower || "背包" in lower || "bag" in lower -> ItemCategory.BACKPACK
        "car" in lower || "车" in lower -> ItemCategory.CAR
        "phone" in lower || "手机" in lower -> ItemCategory.PHONE
        "watch" in lower || "手表" in lower -> ItemCategory.WATCH
        "glass" in lower || "眼镜" in lower -> ItemCategory.GLASSES
        "umbrella" in lower || "伞" in lower -> ItemCategory.UMBRELLA
        "bike" in lower || "自行车" in lower -> ItemCategory.BIKE
        "luggage" in lower || "行李" in lower -> ItemCategory.LUGGAGE
        "cat" in lower || "猫" in lower || "dog" in lower || "狗" in lower -> ItemCategory.PET
        "headphone" in lower || "耳机" in lower -> ItemCategory.HEADPHONE
        "book" in lower || "书" in lower -> ItemCategory.BOOK
        "remote" in lower || "遥控" in lower -> ItemCategory.REMOTE
        else -> ItemCategory.TAG
    }
}

@Composable
fun ItemIconVector(
    category: ItemCategory,
    size: Float,
    color: Color = Color.White.copy(alpha = 0.9f)
) {
    Canvas(modifier = Modifier.size(size.dp)) {
        val w = this.size.width
        val h = this.size.height
        val stroke = (w * 0.065f).coerceAtLeast(1.2f)
        val r = w * 0.09f  // corner radius

        when (category) {
            ItemCategory.KEY -> {
                // Minimalist key: small ring + two teeth
                val cx = w * 0.36f; val cy = h * 0.31f; val ringR = w * 0.14f
                drawCircle(color, ringR, Offset(cx, cy), style = Stroke(stroke))
                drawLine(color, Offset(cx, cy + ringR), Offset(cx, h * 0.68f), stroke, StrokeCap.Round)
                // teeth
                drawLine(color, Offset(cx, h * 0.56f), Offset(cx + w * 0.3f, h * 0.56f), stroke, StrokeCap.Round)
                drawLine(color, Offset(cx, h * 0.68f), Offset(cx + w * 0.22f, h * 0.68f), stroke, StrokeCap.Round)
            }

            ItemCategory.WALLET -> {
                // Horizontal card holder silhouette
                val rrect = Path().apply {
                    addRoundRect(androidx.compose.ui.geometry.RoundRect(
                        w * 0.12f, h * 0.35f, w * 0.88f, h * 0.65f, r, r
                    ))
                }
                drawPath(rrect, color, style = Stroke(stroke))
                // card lines
                drawLine(color, Offset(w * 0.18f, h * 0.45f), Offset(w * 0.82f, h * 0.45f), stroke * 0.6f, StrokeCap.Round)
                drawLine(color, Offset(w * 0.18f, h * 0.53f), Offset(w * 0.65f, h * 0.53f), stroke * 0.6f, StrokeCap.Round)
            }

            ItemCategory.BACKPACK -> {
                // Simple backpack outline
                val path = Path().apply {
                    moveTo(w * 0.3f, h * 0.18f)
                    cubicTo(w * 0.2f, h * 0.18f, w * 0.15f, h * 0.3f, w * 0.15f, h * 0.4f)
                    lineTo(w * 0.15f, h * 0.82f)
                    cubicTo(w * 0.15f, h * 0.9f, w * 0.22f, h * 0.92f, w * 0.3f, h * 0.92f)
                    lineTo(w * 0.7f, h * 0.92f)
                    cubicTo(w * 0.78f, h * 0.92f, w * 0.85f, h * 0.9f, w * 0.85f, h * 0.82f)
                    lineTo(w * 0.85f, h * 0.4f)
                    cubicTo(w * 0.85f, h * 0.3f, w * 0.8f, h * 0.18f, w * 0.7f, h * 0.18f)
                    close()
                }
                drawPath(path, color, style = Stroke(stroke))
                // straps
                drawLine(color, Offset(w * 0.28f, h * 0.18f), Offset(w * 0.28f, h * 0.06f), stroke, StrokeCap.Round)
                drawLine(color, Offset(w * 0.72f, h * 0.18f), Offset(w * 0.72f, h * 0.06f), stroke, StrokeCap.Round)
                drawLine(color, Offset(w * 0.26f, h * 0.06f), Offset(w * 0.74f, h * 0.06f), stroke, StrokeCap.Round)
                // front pocket
                drawLine(color, Offset(w * 0.35f, h * 0.45f), Offset(w * 0.65f, h * 0.45f), stroke * 0.6f, StrokeCap.Round)
            }

            ItemCategory.CAR -> {
                // Front-view car silhouette
                drawLine(color, Offset(w * 0.12f, h * 0.72f), Offset(w * 0.88f, h * 0.72f), stroke, StrokeCap.Round)
                // body
                val body = Path().apply {
                    moveTo(w * 0.18f, h * 0.72f)
                    lineTo(w * 0.18f, h * 0.6f)
                    cubicTo(w * 0.18f, h * 0.48f, w * 0.28f, h * 0.32f, w * 0.38f, h * 0.32f)
                    lineTo(w * 0.62f, h * 0.32f)
                    cubicTo(w * 0.72f, h * 0.32f, w * 0.82f, h * 0.48f, w * 0.82f, h * 0.6f)
                    lineTo(w * 0.82f, h * 0.72f)
                }
                drawPath(body, color, style = Stroke(stroke))
                // windshield
                val glass = Path().apply {
                    moveTo(w * 0.42f, h * 0.35f)
                    lineTo(w * 0.42f, h * 0.52f)
                    cubicTo(w * 0.3f, h * 0.52f, w * 0.22f, h * 0.6f, w * 0.22f, h * 0.65f)
                    moveTo(w * 0.58f, h * 0.35f)
                    lineTo(w * 0.58f, h * 0.52f)
                    cubicTo(w * 0.7f, h * 0.52f, w * 0.78f, h * 0.6f, w * 0.78f, h * 0.65f)
                }
                drawPath(glass, color, style = Stroke(stroke * 0.5f))
                // headlights
                drawCircle(color, w * 0.03f, Offset(w * 0.28f, h * 0.65f))
                drawCircle(color, w * 0.03f, Offset(w * 0.72f, h * 0.65f))
            }

            ItemCategory.PHONE -> {
                // Smartphone: rounded rect with a camera dot
                val phone = Path().apply {
                    addRoundRect(androidx.compose.ui.geometry.RoundRect(
                        w * 0.25f, h * 0.08f, w * 0.75f, h * 0.92f, r * 1.5f, r * 1.5f
                    ))
                }
                drawPath(phone, color, style = Stroke(stroke))
                // screen inner
                drawRoundRect(
                    color, Offset(w * 0.3f, h * 0.18f),
                    Size(w * 0.4f, h * 0.6f), CornerRadius(r * 0.5f),
                    style = Stroke(stroke * 0.4f)
                )
                // camera dot
                drawCircle(color, w * 0.035f, Offset(w * 0.5f, h * 0.25f))
            }

            ItemCategory.WATCH -> {
                // Left band top, left band bottom (from edge curving into the face)
                val cx = w * 0.5f; val cy = h * 0.5f
                // left top band
                val lt = Path().apply {
                    moveTo(w * 0.12f, h * 0.08f)
                    cubicTo(w * 0.26f, h * 0.2f, w * 0.18f, h * 0.38f, cx - w * 0.2f, cy)
                }
                // left bottom band
                val lb = Path().apply {
                    moveTo(w * 0.12f, h * 0.92f)
                    cubicTo(w * 0.26f, h * 0.8f, w * 0.18f, h * 0.62f, cx - w * 0.2f, cy)
                }
                // right top band
                val rt = Path().apply {
                    moveTo(w * 0.88f, h * 0.08f)
                    cubicTo(w * 0.74f, h * 0.2f, w * 0.82f, h * 0.38f, cx + w * 0.2f, cy)
                }
                // right bottom band
                val rb = Path().apply {
                    moveTo(w * 0.88f, h * 0.92f)
                    cubicTo(w * 0.74f, h * 0.8f, w * 0.82f, h * 0.62f, cx + w * 0.2f, cy)
                }
                drawPath(lt, color, style = Stroke(stroke))
                drawPath(lb, color, style = Stroke(stroke))
                drawPath(rt, color, style = Stroke(stroke))
                drawPath(rb, color, style = Stroke(stroke))
                // face
                drawCircle(color, w * 0.2f, Offset(cx, cy), style = Stroke(stroke))
                drawCircle(color, w * 0.03f, Offset(cx, cy))
            }

            ItemCategory.GLASSES -> {
                // Two circles connected by a bridge
                drawCircle(color, w * 0.22f, Offset(w * 0.22f, h * 0.48f), style = Stroke(stroke))
                drawCircle(color, w * 0.22f, Offset(w * 0.78f, h * 0.48f), style = Stroke(stroke))
                // bridge
                drawLine(color, Offset(w * 0.42f, h * 0.48f), Offset(w * 0.58f, h * 0.48f), stroke, StrokeCap.Round)
                // temples (arms)
                drawLine(color, Offset(w * 0.04f, h * 0.48f), Offset(w * 0.14f, h * 0.4f), stroke, StrokeCap.Round)
                drawLine(color, Offset(w * 0.96f, h * 0.48f), Offset(w * 0.86f, h * 0.4f), stroke, StrokeCap.Round)
            }

            ItemCategory.UMBRELLA -> {
                // Arc canopy + handle stick
                drawArc(
                    color, 180f, 180f, false,
                    Offset(w * 0.08f, h * 0.08f),
                    Size(w * 0.84f, h * 0.47f),
                    style = Stroke(stroke)
                )
                // shaft
                drawLine(color, Offset(w * 0.5f, h * 0.35f), Offset(w * 0.5f, h * 0.8f), stroke, StrokeCap.Round)
                // J-handle
                drawArc(
                    color, 0f, 180f, false,
                    Offset(w * 0.38f, h * 0.64f),
                    Size(w * 0.24f, h * 0.3f),
                    style = Stroke(stroke)
                )
            }

            ItemCategory.BIKE -> {
                // Two wheels
                drawCircle(color, w * 0.22f, Offset(w * 0.24f, h * 0.68f), style = Stroke(stroke * 0.8f))
                drawCircle(color, w * 0.22f, Offset(w * 0.76f, h * 0.68f), style = Stroke(stroke * 0.8f))
                // frame
                drawLine(color, Offset(w * 0.24f, h * 0.68f), Offset(w * 0.5f, h * 0.35f), stroke, StrokeCap.Round)
                drawLine(color, Offset(w * 0.76f, h * 0.68f), Offset(w * 0.5f, h * 0.35f), stroke, StrokeCap.Round)
                drawLine(color, Offset(w * 0.24f, h * 0.68f), Offset(w * 0.5f, h * 0.8f), stroke, StrokeCap.Round)
                drawLine(color, Offset(w * 0.76f, h * 0.68f), Offset(w * 0.5f, h * 0.8f), stroke, StrokeCap.Round)
                // seat
                drawLine(color, Offset(w * 0.44f, h * 0.32f), Offset(w * 0.56f, h * 0.32f), stroke, StrokeCap.Round)
                // handlebar
                drawLine(color, Offset(w * 0.44f, h * 0.2f), Offset(w * 0.56f, h * 0.22f), stroke, StrokeCap.Round)
                drawLine(color, Offset(w * 0.5f, h * 0.22f), Offset(w * 0.5f, h * 0.35f), stroke, StrokeCap.Round)
            }

            ItemCategory.LUGGAGE -> {
                // Rectangle case + top handle
                val case = Path().apply {
                    addRoundRect(androidx.compose.ui.geometry.RoundRect(
                        w * 0.2f, h * 0.32f, w * 0.8f, h * 0.88f, r, r
                    ))
                }
                drawPath(case, color, style = Stroke(stroke))
                // handle
                drawLine(color, Offset(w * 0.38f, h * 0.32f), Offset(w * 0.38f, h * 0.12f), stroke, StrokeCap.Round)
                drawLine(color, Offset(w * 0.62f, h * 0.32f), Offset(w * 0.62f, h * 0.12f), stroke, StrokeCap.Round)
                drawLine(color, Offset(w * 0.35f, h * 0.12f), Offset(w * 0.65f, h * 0.12f), stroke, StrokeCap.Round)
                // pull handle
                drawLine(color, Offset(w * 0.5f, h * 0.88f), Offset(w * 0.5f, h * 0.2f), stroke, StrokeCap.Round)
                drawLine(color, Offset(w * 0.46f, h * 0.18f), Offset(w * 0.54f, h * 0.18f), stroke, StrokeCap.Round)
            }

            ItemCategory.PET -> {
                // Paw print: central pad + 4 toe pads
                val padCx = w * 0.5f; val padCy = h * 0.6f
                val padR = w * 0.15f
                val toeR = w * 0.08f
                // main pad
                val pad = Path().apply {
                    moveTo(padCx - padR, padCy)
                    cubicTo(padCx - padR, padCy + padR * 0.8f, padCx + padR, padCy + padR * 0.8f, padCx + padR, padCy)
                    cubicTo(padCx + padR * 0.5f, padCy - padR * 0.4f, padCx - padR * 0.5f, padCy - padR * 0.4f, padCx - padR, padCy)
                }
                drawPath(pad, color, style = Stroke(stroke))
                // 4 toes
                val toes = listOf(
                    Offset(padCx - w * 0.22f, h * 0.32f),
                    Offset(padCx - w * 0.07f, h * 0.26f),
                    Offset(padCx + w * 0.07f, h * 0.26f),
                    Offset(padCx + w * 0.22f, h * 0.32f)
                )
                toes.forEach { t ->
                    drawCircle(color, toeR, t, style = Stroke(stroke))
                }
            }

            ItemCategory.HEADPHONE -> {
                // Headband arc + two ear cups
                val bandArc = Path().apply {
                    moveTo(w * 0.08f, h * 0.45f)
                    cubicTo(w * 0.08f, h * 0.08f, w * 0.92f, h * 0.08f, w * 0.92f, h * 0.45f)
                }
                drawPath(bandArc, color, style = Stroke(stroke))
                // left cup
                drawRoundRect(
                    color, Offset(w * 0.04f, h * 0.35f),
                    Size(w * 0.14f, h * 0.27f), CornerRadius(r * 0.7f),
                    style = Stroke(stroke)
                )
                // right cup
                drawRoundRect(
                    color, Offset(w * 0.82f, h * 0.35f),
                    Size(w * 0.14f, h * 0.27f), CornerRadius(r * 0.7f),
                    style = Stroke(stroke)
                )
            }

            ItemCategory.BOOK -> {
                // Book outline with spine
                val book = Path().apply {
                    addRoundRect(androidx.compose.ui.geometry.RoundRect(
                        w * 0.25f, h * 0.2f, w * 0.82f, h * 0.88f, r * 0.4f, r * 0.4f
                    ))
                }
                drawPath(book, color, style = Stroke(stroke))
                // spine line
                drawLine(color, Offset(w * 0.25f, h * 0.2f), Offset(w * 0.25f, h * 0.88f), stroke * 1.3f, StrokeCap.Round)
                // page lines
                for (y in listOf(0.35f, 0.45f, 0.55f)) {
                    drawLine(color, Offset(w * 0.32f, h * y), Offset(w * 0.7f, h * y), stroke * 0.5f, StrokeCap.Round)
                }
            }

            ItemCategory.REMOTE -> {
                // Slim rectangle with a circle button
                val body = Path().apply {
                    addRoundRect(androidx.compose.ui.geometry.RoundRect(
                        w * 0.22f, h * 0.12f, w * 0.78f, h * 0.88f, r * 1.2f, r * 1.2f
                    ))
                }
                drawPath(body, color, style = Stroke(stroke))
                // center button (circle)
                drawCircle(color, w * 0.1f, Offset(w * 0.5f, h * 0.48f), style = Stroke(stroke * 0.8f))
                drawCircle(color, w * 0.03f, Offset(w * 0.5f, h * 0.48f))
                // D-pad cross
                val dpadR = w * 0.14f
                drawLine(color, Offset(w * 0.5f - dpadR, h * 0.48f), Offset(w * 0.5f + dpadR, h * 0.48f), stroke * 0.4f)
                drawLine(color, Offset(w * 0.5f, h * 0.48f - dpadR), Offset(w * 0.5f, h * 0.48f + dpadR), stroke * 0.4f)
            }

            ItemCategory.TAG -> {
                // Angled label tag with a hole
                val tag = Path().apply {
                    moveTo(w * 0.2f, h * 0.15f)
                    lineTo(w * 0.8f, h * 0.15f)
                    lineTo(w * 0.8f, h * 0.55f)
                    lineTo(w * 0.5f, h * 0.85f)
                    lineTo(w * 0.2f, h * 0.55f)
                    close()
                }
                drawPath(tag, color, style = Stroke(stroke))
                // hole
                drawCircle(color, w * 0.05f, Offset(w * 0.38f, h * 0.28f), style = Stroke(stroke * 0.6f))
                // tie string
                drawLine(color, Offset(w * 0.38f, h * 0.28f), Offset(w * 0.38f, h * 0.06f), stroke * 0.6f, StrokeCap.Round)
            }
        }
    }
}

@Composable
fun MyDeviceItem(name: String, isCurrent: Boolean, status: String, onClick: () -> Unit = {}) {
    val bgColor = if (isCurrent) CardGlass else CardDark
    val dotColor = if (isCurrent) StatusDotGreen else Color(0xFF2A332F)
    val shadowElevation = if (isCurrent) 2.dp else 0.dp

    Card(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 24.dp, vertical = 4.dp)
            .shadow(shadowElevation, RoundedCornerShape(14.dp))
            .clickable { onClick() },
        shape = RoundedCornerShape(14.dp),
        colors = CardDefaults.cardColors(containerColor = bgColor)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 14.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            // Item icon
            Box(
                modifier = Modifier
                    .size(36.dp)
                    .clip(RoundedCornerShape(10.dp))
                    .background(Color(0xFF0F1A15)),
                contentAlignment = Alignment.Center
            ) {
                ItemIconVector(
                    category = itemCategory(name),
                    size = 20f,
                    color = Color.White.copy(alpha = 0.75f)
                )
            }

            Spacer(modifier = Modifier.width(12.dp))

            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = name,
                    style = MaterialTheme.typography.titleSmall,
                    color = TextPrimary,
                    fontWeight = FontWeight.Medium
                )
                Text(
                    text = if (isCurrent) "已连接" else "点击连接",
                    style = MaterialTheme.typography.labelSmall,
                    color = if (isCurrent) StatusNear else TextMuted
                )
            }

            // Status dot
            Box(
                modifier = Modifier
                    .size(8.dp)
                    .clip(CircleShape)
                    .background(dotColor)
            )
        }
    }
}
