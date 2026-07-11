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
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.Fill
import androidx.compose.ui.graphics.drawscope.rotate as rotateDraw
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
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
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val bluetoothManager = getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager
        bluetoothAdapter = bluetoothManager.adapter

        checkAndRequestPermissions()

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
                        feedbackManager.stop()
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
                                feedbackManager.stop()
                                bleManager.disconnect()
                            },
                            onConnectSavedDevice = { mac ->
                                feedbackManager.stop()
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

    private fun checkAndRequestPermissions() {
        val required = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            arrayOf(
                Manifest.permission.BLUETOOTH_CONNECT,
                Manifest.permission.BLUETOOTH_SCAN,
                Manifest.permission.ACCESS_FINE_LOCATION,
                Manifest.permission.UWB_RANGING,
                Manifest.permission.VIBRATE
            )
        } else {
            arrayOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.VIBRATE)
        }
        val missing = required.filter {
            ContextCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED
        }.toTypedArray()
        if (missing.isNotEmpty()) requestPermissionLauncher.launch(missing)
    }

    override fun onDestroy() {
        super.onDestroy()
        feedbackManager.stop()
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
                text = "Halo Tag",
                style = MaterialTheme.typography.headlineLarge,
                color = TextOnDark,
                fontWeight = FontWeight.SemiBold
            )

            Spacer(modifier = Modifier.height(6.dp))

            // Subtitle
            Text(
                text = "Find your items...",
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
                            text = "Scanning for tags...",
                            style = MaterialTheme.typography.labelLarge,
                            color = PrimaryGreen,
                            fontWeight = FontWeight.SemiBold
                        )
                    }
                } else {
                    Text(
                        text = "🔍 Scan for Tags",
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
                    isScanning && devices.isEmpty() -> "Searching..."
                    devices.isNotEmpty() -> "${devices.size} device(s)"
                    else -> "Tap scan to search for nearby tags"
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
            Text(
                text = "📡",
                fontSize = 48.sp
            )
            Spacer(modifier = Modifier.height(12.dp))
            Text(
                text = "No devices found",
                style = MaterialTheme.typography.bodyLarge,
                color = Color.White.copy(alpha = 0.5f)
            )
            Text(
                text = "Tap scan to search for nearby tags",
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
                        text = "Bound tag",
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
                Text("New Tag Found!", color = PrimaryGreen, fontWeight = FontWeight.SemiBold)
            },
            text = {
                Column {
                    Text(
                        text = "What is this tag attached to?",
                        style = MaterialTheme.typography.bodyMedium,
                        color = TextSecondary
                    )
                    Spacer(modifier = Modifier.height(12.dp))
                    OutlinedTextField(
                        value = bindName,
                        onValueChange = { bindName = it },
                        label = { Text("Item name", color = TextSecondary) },
                        placeholder = { Text("e.g. My Keys, Wallet...", color = TextMuted) },
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
                    Text("Save", color = PrimaryGreen, fontWeight = FontWeight.SemiBold)
                }
            },
            dismissButton = {
                TextButton(onClick = { showBindDialog = false; bindName = "" }) {
                    Text("Skip", color = TextSecondary)
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
                            modifier = Modifier.fillMaxSize()
                        )
                    }

                    Spacer(modifier = Modifier.height(20.dp))

                    // Status label
                    val statusLabel = when {
                        isAligned -> "FOUND"
                        isGuided -> "GUIDING"
                        isSearching -> "SEARCHING..."
                        isError -> "ERROR"
                        else -> "READY"
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
                                text = "🔊 Play Sound",
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
                                text = "🧭 Direction",
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
                        FindingStatus.IDLE -> "Start Finding"
                        FindingStatus.SENDING -> "Waiting for OOB ACK..."
                        FindingStatus.BOARD_STARTING -> "Waiting for board startup..."
                        FindingStatus.BOARD_STARTED -> "Board Ready: Restart"
                        FindingStatus.RANGING -> "Waiting for UWB result..."
                        FindingStatus.SUCCESS -> String.format("✅ Found It!  (%.1f m)", uwbData.distanceMeters)
                        FindingStatus.FAILED -> "Write Failed: Retry"
                        FindingStatus.TIMEOUT -> "ACK Timeout: Retry"
                        FindingStatus.UWB_ERROR -> "Range Failed: Retry"
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
                        text = "MY DEVICES",
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
                            text = "No saved tags. Scan and tap ＋ to bind a tag.",
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
                    text = "Halo Tag",
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
                    text = "Disconnect",
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

    Box(modifier = modifier, contentAlignment = Alignment.Center) {
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

            // Pseudo-3D perspective: compress Y axis to simulate arrow laying on a surface
            val perspectiveY = if (isAligned) 0.92f else 0.5f

            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .scale(scaleX = 1f, scaleY = perspectiveY)
                    .rotate(animatedAngle)
                    .offset(y = floatOffset.dp),
                contentAlignment = Alignment.Center
            ) {
                PrecisionArrow(
                    modifier = Modifier.size(90.dp),
                    isAligned = isAligned
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
fun PrecisionArrow(modifier: Modifier = Modifier, isAligned: Boolean = false) {
    val arrowAlpha = if (isAligned) 0.95f else 0.85f
    val strokeW = 9.dp  // thick, bold line

    Canvas(modifier = modifier) {
        val w = size.width
        val h = size.height
        val cx = w / 2f
        val sw = strokeW.toPx()

        // Outer glow
        val glowW = sw * 1.8f
        val tipY = h * 0.08f
        val midY = h * 0.42f
        val baseY = h * 0.92f
        val headHalfW = w * 0.42f
        val shaftHalfW = w * 0.14f

        // === Glow layer ===
        val glowColor = Color.White.copy(alpha = arrowAlpha * 0.3f)

        // Shaft glow
        drawLine(glowColor, Offset(cx, midY), Offset(cx, baseY), glowW, StrokeCap.Round)
        // Arrowhead left wing glow
        drawLine(glowColor, Offset(cx, tipY), Offset(cx - headHalfW, midY), glowW, StrokeCap.Round)
        // Arrowhead right wing glow
        drawLine(glowColor, Offset(cx, tipY), Offset(cx + headHalfW, midY), glowW, StrokeCap.Round)

        // === Solid arrow ===
        val arrowColor = Color.White.copy(alpha = arrowAlpha)

        // Shaft
        drawLine(arrowColor, Offset(cx, midY), Offset(cx, baseY), sw, StrokeCap.Round)
        // Arrowhead left
        drawLine(arrowColor, Offset(cx, tipY), Offset(cx - headHalfW, midY), sw, StrokeCap.Round)
        // Arrowhead right
        drawLine(arrowColor, Offset(cx, tipY), Offset(cx + headHalfW, midY), sw, StrokeCap.Round)
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
        FindingStatus.IDLE -> "Ready to search"
        FindingStatus.SENDING, FindingStatus.BOARD_STARTING -> "Waking base station..."
        FindingStatus.BOARD_STARTED, FindingStatus.RANGING -> "Starting ranging..."
        FindingStatus.SUCCESS -> "Near (Approx. ${String.format("%.1f", uwbData.distanceMeters)} m)"
        FindingStatus.FAILED -> "Write error — retry"
        FindingStatus.TIMEOUT -> "No response from tag"
        FindingStatus.UWB_ERROR -> "UWB ranging failure"
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
                Text("🔑", fontSize = 22.sp)
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
                    text = "STATUS: $statusText",
                    style = MaterialTheme.typography.bodySmall,
                    color = statusColor,
                    fontWeight = FontWeight.Medium
                )
                Text(
                    text = "Last Seen: Home (2 min ago)",
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

private fun itemIcon(name: String): String {
    val lower = name.lowercase()
    return when {
        "key" in lower || "钥匙" in lower -> "🔑"
        "wallet" in lower || "钱包" in lower -> "👛"
        "backpack" in lower || "背包" in lower || "bag" in lower -> "🎒"
        "car" in lower || "车" in lower -> "🚗"
        "phone" in lower || "手机" in lower -> "📱"
        "watch" in lower || "手表" in lower -> "⌚"
        "glass" in lower || "眼镜" in lower -> "👓"
        "umbrella" in lower || "伞" in lower -> "☂️"
        "bike" in lower || "自行车" in lower -> "🚲"
        "luggage" in lower || "行李" in lower -> "🧳"
        "cat" in lower || "猫" in lower -> "🐱"
        "dog" in lower || "狗" in lower -> "🐶"
        "headphone" in lower || "耳机" in lower -> "🎧"
        "book" in lower || "书" in lower -> "📖"
        "remote" in lower || "遥控" in lower -> "📡"
        else -> "🏷️"
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
                Text(text = itemIcon(name), fontSize = 18.sp)
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
                    text = if (isCurrent) "Connected" else "Tap to connect",
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
