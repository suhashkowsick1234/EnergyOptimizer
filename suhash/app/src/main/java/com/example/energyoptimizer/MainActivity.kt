package com.example.energyoptimizer

import android.app.*
import android.content.*
import android.content.pm.ApplicationInfo
import android.graphics.Bitmap
import android.graphics.Canvas
import android.net.Uri
import android.os.BatteryManager
import android.os.Build
import android.os.Bundle
import android.os.PowerManager
import android.os.Process
import android.provider.Settings
import android.util.Log
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.animation.*
import androidx.compose.animation.core.*
import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.*
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import androidx.core.content.edit
import com.example.energyoptimizer.ui.theme.EnergyOptimizerTheme
import kotlinx.coroutines.delay
import java.text.SimpleDateFormat
import java.util.*

// --- BRAND PALETTE ---
val DarkBg = Color(0xFF080B11)
val CardColor = Color(0xFF11161D)
val CyanAccent = Color(0xFF00E5FF)
val SoftPurple = Color(0xFF7C83FF)
val TextGray = Color(0xFF8E99A5)
val StatePillBg = Color(0xFF161C24)
val SuccessGreen = Color(0xFF00E676)
val WarningOrange = Color(0xFFFF9800)
val CriticalRed = Color(0xFFFF4D4D)

enum class PowerProfile(
    val label: String,
    val syncIntervalMs: Long,
    val icon: ImageVector,
    val statusText: String,
    val intensity: Float 
) {
    PERFORMANCE("Performance", 2000L, Icons.Default.Speed, "Monitoring every 2 seconds", 1.0f),
    BALANCED("Balanced", 5000L, Icons.Default.Balance, "Monitoring every 5 seconds", 0.7f),
    POWER_SAVER("Power Saver", 15000L, Icons.Default.BatteryFull, "Monitoring every 15 seconds", 0.4f),
    NIGHT("Night", 30000L, Icons.Default.Bedtime, "Monitoring every 30 seconds", 0.2f),
    STUDENT("Student", 10000L, Icons.Default.School, "Monitoring every 10 seconds (Focus active)", 0.7f)
}

data class AppUsageInfo(
    val pkg: String,
    val name: String,
    val screenTimeMs: Long,
    val backgroundTimeMs: Long,
    val batteryPercent: String,
    val batteryPercentValue: Float // for sorting
)

data class OptimizationSuggestion(
    val title: String,
    val impact: String, 
    val color: Color
)

class MainActivity : ComponentActivity() {
    
    private val showUsageCard = mutableStateOf(false)
    private val showBatteryCard = mutableStateOf(false)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        
        checkPermissionsAndStart()

        setContent {
            EnergyOptimizerTheme {
                Surface(modifier = Modifier.fillMaxSize(), color = DarkBg) {
                    EnergyOptimizerScreen(showUsageCard, showBatteryCard)
                }
            }
        }
    }

    override fun onResume() {
        super.onResume()
        checkPermissionsAndStart()
    }

    fun checkPermissionsAndStart() {
        val hasUsage = hasUsagePermission()
        val hasBatteryOpt = isIgnoringBatteryOptimizations()
        
        showUsageCard.value = !hasUsage
        showBatteryCard.value = !hasBatteryOpt

        if (hasUsage) {
            startTrackingServiceIfNeeded()
        } else {
            stopService(Intent(this, UsageTrackingService::class.java))
        }
    }

    private fun hasUsagePermission(): Boolean {
        val appOps = getSystemService(Context.APP_OPS_SERVICE) as AppOpsManager
        val mode = appOps.checkOpNoThrow(
            AppOpsManager.OPSTR_GET_USAGE_STATS,
            Process.myUid(),
            packageName
        )
        return mode == AppOpsManager.MODE_ALLOWED
    }

    private fun isIgnoringBatteryOptimizations(): Boolean {
        val pm = getSystemService(Context.POWER_SERVICE) as PowerManager
        return pm.isIgnoringBatteryOptimizations(packageName)
    }

    private fun startTrackingServiceIfNeeded() {
        if (!isServiceRunning(UsageTrackingService::class.java)) {
            val intent = Intent(this, UsageTrackingService::class.java)
            ContextCompat.startForegroundService(this, intent)
        }
    }

    private fun isServiceRunning(serviceClass: Class<*>): Boolean {
        val manager = getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
        @Suppress("DEPRECATION")
        return manager.getRunningServices(Int.MAX_VALUE)
            .any { it.service.className == serviceClass.name }
    }
}

@Composable
fun EnergyOptimizerScreen(showUsageCard: MutableState<Boolean>, showBatteryCard: MutableState<Boolean>) {
    val context = LocalContext.current
    val scrollState = rememberScrollState()
    
    // Initial Battery Value Capture
    val initialBatteryIntent = context.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
    val initialLevel = initialBatteryIntent?.getIntExtra(BatteryManager.EXTRA_LEVEL, -1) ?: -1
    val initialScale = initialBatteryIntent?.getIntExtra(BatteryManager.EXTRA_SCALE, -1) ?: -1
    val initialPercent = if (initialScale > 0) (initialLevel * 100f / initialScale).toInt() else 0
    val initialStatus = initialBatteryIntent?.getIntExtra(BatteryManager.EXTRA_STATUS, -1) ?: -1

    // UI State
    var batteryLevel by remember { mutableIntStateOf(initialPercent) }
    var batteryStatusInt by remember { mutableIntStateOf(initialStatus) }
    var drainRate by remember { mutableFloatStateOf(-1f) } 
    var screenOnMs by remember { mutableLongStateOf(0L) }
    var screenOffMs by remember { mutableLongStateOf(0L) }
    var appUsageList by remember { mutableStateOf<List<AppUsageInfo>>(emptyList()) }
    var batteryHistory by remember { mutableStateOf<List<Float>>(emptyList()) }
    var maxConsumer by remember { mutableStateOf("No active app usage") }
    var suggestions by remember { mutableStateOf<List<OptimizationSuggestion>>(emptyList()) }
    var activeProfile by remember { mutableStateOf(PowerProfile.BALANCED) }
    var weeklyData by remember { mutableStateOf<List<Long>>(emptyList()) }
    var yesterdayUsageMs by remember { mutableLongStateOf(0L) }
    var lastUpdateTime by remember { mutableStateOf("Updated just now") }
    var isDataInitializing by remember { mutableStateOf(true) }
    var systemInsight by remember { mutableStateOf("Analyzing system behavior...") }
    
    val smoothedPercentages = remember { mutableMapOf<String, Float>() }
    
    val prefs = context.getSharedPreferences("usage_tracking_v15", Context.MODE_PRIVATE)
    val uiPrefs = context.getSharedPreferences("ui_state_v1", Context.MODE_PRIVATE)

    // Load Profile
    LaunchedEffect(Unit) {
        val savedProfile = uiPrefs.getString("active_profile", PowerProfile.BALANCED.name)
        activeProfile = PowerProfile.entries.find { it.name == savedProfile } ?: PowerProfile.BALANCED
    }

    // Battery Broadcast Receiver
    DisposableEffect(context) {
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context?, intent: Intent?) {
                intent?.let {
                    val level = it.getIntExtra(BatteryManager.EXTRA_LEVEL, -1)
                    val scale = it.getIntExtra(BatteryManager.EXTRA_SCALE, -1)
                    batteryLevel = if (scale > 0) (level * 100f / scale).toInt() else 0
                    batteryStatusInt = it.getIntExtra(BatteryManager.EXTRA_STATUS, -1)
                }
            }
        }
        context.registerReceiver(receiver, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
        onDispose { context.unregisterReceiver(receiver) }
    }

    // Main Refresh Loop
    LaunchedEffect(activeProfile) {
        while (true) {
            val hasPermission = hasUsagePermission(context)
            val now = System.currentTimeMillis()
            val startOfDay = getStartOfDay()

            if (hasPermission) {
                // 1. Load Aggregate Data from Storage
                val usageDataSet = prefs.getStringSet("today_usage_map", emptySet()) ?: emptySet()
                val usageMap = usageDataSet.associate { 
                    val parts = it.split(":")
                    parts[0] to parts[1].toLong()
                }.toMutableMap()
                
                // 2. Add current live session (Live update for foreground app)
                val activePkg = prefs.getString("active_pkg", null)
                val activeStart = prefs.getLong("active_start", 0L)
                if (activePkg != null && activeStart > 0 && activeStart >= startOfDay) {
                    val liveDuration = now - activeStart
                    if (liveDuration > 0) {
                        usageMap[activePkg] = (usageMap.getOrDefault(activePkg, 0L)) + liveDuration
                    }
                }

                val finalUsageMap = usageMap.filter { it.value >= 1000 }
                
                screenOnMs = finalUsageMap.values.sum()
                screenOffMs = (now - startOfDay) - screenOnMs

                // ⭐ HYBRID BATTERY USAGE LOGIC (FAST + REAL)
                val dayStartLevel = prefs.getInt("day_start_level", -1)
                val totalDrop = if (dayStartLevel != -1) (dayStartLevel - batteryLevel).coerceAtLeast(0) else 0
                val totalUsageTime = finalUsageMap.values.sum()

                val currentAppList = finalUsageMap.map { (pkg, ms) ->
                    val totalAppTime = ms
                    
                    val targetPctValue = if (totalUsageTime == 0L) {
                        0f
                    } else if (totalDrop >= 1) {
                        // REAL MODE (accurate)
                        (totalAppTime.toFloat() / totalUsageTime) * totalDrop
                    } else {
                        // FAST MODE (instant estimation)
                        (totalAppTime.toFloat() / totalUsageTime) * 100f * 0.05f
                    }
                    
                    // Smooth transition logic (Lerp between cycles)
                    val lastVal = smoothedPercentages[pkg] ?: targetPctValue
                    val batteryPctValue = if (isDataInitializing) targetPctValue else {
                        lastVal + (targetPctValue - lastVal) * 0.4f
                    }
                    smoothedPercentages[pkg] = batteryPctValue
                    
                    val batteryPctStr = if (totalUsageTime == 0L) {
                        "0%"
                    } else if (batteryPctValue < 0.1f) {
                        "0.1%"
                    } else {
                        "%.1f%%".format(batteryPctValue)
                    }

                    AppUsageInfo(
                        pkg = pkg, 
                        name = getAppLabel(context, pkg), 
                        screenTimeMs = ms,
                        backgroundTimeMs = 0L,
                        batteryPercent = batteryPctStr,
                        batteryPercentValue = batteryPctValue
                    )
                }.sortedByDescending { it.batteryPercentValue }

                appUsageList = currentAppList

                maxConsumer = if (appUsageList.isNotEmpty()) {
                    appUsageList.first().name 
                } else {
                    "No active app usage"
                }

                // Update Insight
                val insightMap = currentAppList.associate { it.name to it.screenTimeMs }
                systemInsight = generateInsight(insightMap, totalDrop.toFloat(), screenOnMs, yesterdayUsageMs)

                // Battery Drain Calculation for telemetry
                val elapsedMinutes = (now - startOfDay) / 60000
                if (elapsedMinutes >= 10 && totalDrop >= 1) {
                    drainRate = (totalDrop.toFloat() / elapsedMinutes) * 60
                } else {
                    drainRate = -1f 
                }

                val yesterdayDataSet = prefs.getStringSet("yesterday_usage_map", emptySet()) ?: emptySet()
                yesterdayUsageMs = yesterdayDataSet.sumOf { it.split(":")[1].toLong() }

                val weeklyStr = prefs.getString("weekly_totals", "") ?: ""
                weeklyData = weeklyStr.split("|").filter { it.isNotEmpty() }.map { 
                    val p = it.split(":")
                    if (p.size == 2) p[1].toLong() else 0L
                }

                isDataInitializing = false
            }

            val historyStr = uiPrefs.getString("battery_history", "") ?: ""
            batteryHistory = if (historyStr.isEmpty()) emptyList() else historyStr.split(",").map { it.toFloat() }

            lastUpdateTime = "Updated just now"
            delay(5000)
        }
    }

    val batteryStatusStr = when (batteryStatusInt) {
        BatteryManager.BATTERY_STATUS_CHARGING -> "CHARGING"
        BatteryManager.BATTERY_STATUS_FULL -> "FULLY CHARGED"
        BatteryManager.BATTERY_STATUS_DISCHARGING -> "DISCHARGING"
        BatteryManager.BATTERY_STATUS_NOT_CHARGING -> "NOT CHARGING"
        else -> "IDLE"
    }

    Column(
        modifier = Modifier.fillMaxSize().verticalScroll(scrollState).padding(bottom = 32.dp)
    ) {
        HeaderSection(activeProfile, lastUpdateTime)
        
        if (showUsageCard.value) {
            PermissionCard(context)
        } else {
            StateChipSection(batteryStatusStr)
            BatteryCircleSection(batteryLevel, batteryStatusInt, drainRate)
            
            // System Insight Line
            Text(
                text = systemInsight,
                color = TextGray.copy(alpha = 0.8f),
                fontSize = 13.sp,
                textAlign = TextAlign.Center,
                modifier = Modifier.fillMaxWidth().padding(horizontal = 20.dp).offset(y = (-16).dp)
            )

            if (showBatteryCard.value) {
                BackgroundOptimizationCard()
            }

            BatteryTelemetrySection(batteryHistory)
            AppActivitySection(screenOnMs, screenOffMs, isDataInitializing)
            OptimizationEngineSection(suggestions)
            PowerProfilesSection(activeProfile) { profile ->
                activeProfile = profile
                uiPrefs.edit { putString("active_profile", profile.name) }
                Toast.makeText(context, "${profile.label} Mode Activated", Toast.LENGTH_SHORT).show()
                val intent = Intent(context, UsageTrackingService::class.java).apply {
                    action = "com.example.energyoptimizer.ACTION_PROFILE_CHANGED"
                    putExtra("profile_name", profile.name)
                }
                context.startService(intent)
            }
            InfoCardsSection(maxConsumer, drainRate)
            DailyUsageSection(weeklyData, screenOnMs, isDataInitializing)
            AppUsageListSection(appUsageList, isDataInitializing, screenOnMs)
            HistoryLogSection(screenOnMs, yesterdayUsageMs)
        }
    }
}

@Composable
fun BackgroundOptimizationCard() {
    val context = LocalContext.current
    Card(
        modifier = Modifier.padding(horizontal = 20.dp, vertical = 8.dp).fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = WarningOrange.copy(0.1f)),
        shape = RoundedCornerShape(14.dp),
        border = BorderStroke(1.dp, WarningOrange.copy(0.3f))
    ) {
        Column(Modifier.padding(16.dp), horizontalAlignment = Alignment.CenterHorizontally) {
            Text("Background Monitoring Required", color = Color.White, fontSize = 14.sp, fontWeight = FontWeight.Bold)
            Text("Allow background activity for accurate energy tracking and stable monitoring.", color = TextGray, fontSize = 12.sp, textAlign = TextAlign.Center)
            Spacer(Modifier.height(12.dp))
            Button(
                onClick = { 
                    val intent = Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS).apply {
                        data = Uri.parse("package:${context.packageName}")
                    }
                    try { context.startActivity(intent) } catch (e: Exception) {
                        context.startActivity(Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS))
                    }
                },
                colors = ButtonDefaults.buttonColors(containerColor = WarningOrange),
                shape = RoundedCornerShape(8.dp)
            ) {
                Text("Allow", color = DarkBg, fontWeight = FontWeight.Bold)
            }
        }
    }
}

@Composable
fun HeaderSection(profile: PowerProfile, lastUpdate: String) {
    Box(modifier = Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 24.dp)) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.size(8.dp).clip(CircleShape).background(SuccessGreen))
                Spacer(Modifier.width(8.dp))
                Text("Sync: ${profile.syncIntervalMs/1000}s (${profile.label})", color = Color.White, fontSize = 13.sp)
            }
            Text(lastUpdate, color = CyanAccent, fontSize = 13.sp)
        }
        Text(
            "Energy Optimizer Pro",
            modifier = Modifier.fillMaxWidth().padding(top = 40.dp),
            textAlign = TextAlign.Center,
            color = Color.White,
            fontSize = 24.sp,
            fontWeight = FontWeight.Bold
        )
    }
}

@Composable
fun StateChipSection(status: String) {
    Box(
        modifier = Modifier.fillMaxWidth().padding(top = 12.dp),
        contentAlignment = Alignment.Center
    ) {
        Surface(color = StatePillBg, shape = RoundedCornerShape(8.dp)) {
            Text(
                "STATE: $status", 
                modifier = Modifier.padding(horizontal = 48.dp, vertical = 8.dp),
                color = TextGray, 
                fontSize = 12.sp, fontWeight = FontWeight.Bold, letterSpacing = 1.2.sp
            )
        }
    }
}

@Composable
fun BatteryCircleSection(level: Int, statusInt: Int, drain: Float) {
    val isCharging = statusInt == BatteryManager.BATTERY_STATUS_CHARGING ||
                     statusInt == BatteryManager.BATTERY_STATUS_FULL
    
    val isDischarging = statusInt == BatteryManager.BATTERY_STATUS_DISCHARGING

    val statusStr = when (statusInt) {
        BatteryManager.BATTERY_STATUS_CHARGING -> "CHARGING"
        BatteryManager.BATTERY_STATUS_FULL -> "FULLY CHARGED"
        BatteryManager.BATTERY_STATUS_DISCHARGING -> "DISCHARGING"
        else -> "IDLE"
    }

    val isHighDrain = drain > 15.0f
    val indicatorColor = if (isCharging) SuccessGreen else if (isHighDrain) CriticalRed else SuccessGreen
    val triangleIcon = if (isCharging) "▲" else if (isDischarging) "▼" else "●"
    
    val subtext = when {
        isCharging -> "Charging in progress"
        isDischarging -> {
            if (drain < 0) "Monitoring energy flow..."
            else if (drain < 1.0f) "Battery stable" 
            else {
                val trend = if (isHighDrain) "Increased activity detected" else "Stable activity"
                "$trend (~%.1f%%/hr)".format(drain)
            }
        }
        else -> "Monitoring energy flow..."
    }
    
    Box(
        modifier = Modifier.fillMaxWidth().padding(top = 32.dp).height(260.dp),
        contentAlignment = Alignment.Center
    ) {
        Canvas(modifier = Modifier.size(240.dp)) {
            drawCircle(color = Color.White.copy(alpha = 0.08f), style = Stroke(width = 12.dp.toPx()))
            drawArc(
                brush = Brush.sweepGradient(0f to SoftPurple, 0.5f to CyanAccent, 1f to SoftPurple),
                startAngle = -90f, sweepAngle = (level / 100f) * 360f, useCenter = false,
                style = Stroke(width = 12.dp.toPx(), cap = StrokeCap.Round)
            )
        }
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text("$level%", color = Color.White, fontSize = 56.sp, fontWeight = FontWeight.Bold)
            Spacer(Modifier.height(4.dp))
            Text(statusStr, color = CyanAccent, fontSize = 15.sp, fontWeight = FontWeight.Bold, letterSpacing = 1.2.sp)
            Spacer(Modifier.height(12.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(triangleIcon, color = indicatorColor, fontSize = 12.sp)
                Spacer(Modifier.width(6.dp))
                Text(subtext, color = TextGray, fontSize = 13.sp)
            }
        }
    }
    Spacer(Modifier.height(32.dp))
}

@Composable
fun PermissionCard(context: Context) {
    Card(
        modifier = Modifier.padding(horizontal = 20.dp, vertical = 8.dp).fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = CyanAccent.copy(0.1f)),
        shape = RoundedCornerShape(14.dp),
        border = BorderStroke(1.dp, CyanAccent.copy(0.3f))
    ) {
        Column(Modifier.padding(16.dp), horizontalAlignment = Alignment.CenterHorizontally) {
            Text("Usage Access Required", color = Color.White, fontSize = 14.sp, fontWeight = FontWeight.Bold)
            Text("Enable usage access to track app activity and battery usage.", color = TextGray, fontSize = 12.sp, textAlign = TextAlign.Center)
            Spacer(Modifier.height(12.dp))
            Button(
                onClick = { context.startActivity(Intent(Settings.ACTION_USAGE_ACCESS_SETTINGS)) },
                colors = ButtonDefaults.buttonColors(containerColor = CyanAccent),
                shape = RoundedCornerShape(8.dp)
            ) {
                Text("Grant Access", color = DarkBg, fontWeight = FontWeight.Bold)
            }
        }
    }
}

@Composable
fun BatteryTelemetrySection(history: List<Float>) {
    Column(modifier = Modifier.padding(horizontal = 20.dp)) {
        SectionTitle(Icons.Default.Timeline, "LIVE BATTERY TELEMETRY (REAL SAMPLES)")
        Box(modifier = Modifier.fillMaxWidth().height(100.dp)) {
            if (history.isEmpty()) {
                Text("Collecting data...", color = TextGray, fontSize = 12.sp, modifier = Modifier.align(Alignment.Center))
            } else {
                Canvas(modifier = Modifier.fillMaxSize()) {
                    if (history.size > 1) {
                        val path = Path()
                        val stepX = size.width / (history.size - 1).toFloat()
                        history.forEachIndexed { index, valLevel ->
                            val x = index * stepX
                            val y = size.height * (1f - valLevel / 100f)
                            if (index == 0) path.moveTo(x, y) else path.lineTo(x, y)
                        }
                        drawPath(path, color = CyanAccent, style = Stroke(width = 1.5.dp.toPx()))
                    } else {
                        val y = size.height * (1f - history[0] / 100f)
                        drawLine(color = CyanAccent, start = Offset(0f, y), end = Offset(size.width, y), strokeWidth = 1.5.dp.toPx())
                    }
                }
            }
        }
        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            listOf("-60m", "-45m", "-30m", "-15m", "Now").forEach {
                Text(it, color = CyanAccent.copy(alpha = 0.8f), fontSize = 10.sp)
            }
        }
    }
    Spacer(Modifier.height(36.dp))
}

@Composable
fun AppActivitySection(onMs: Long, offMs: Long, isInitializing: Boolean) {
    SectionTitle(Icons.Default.Apps, "APP AND SYSTEM ACTIVITY")
    Card(
        modifier = Modifier.padding(horizontal = 20.dp).fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = CardColor),
        shape = RoundedCornerShape(14.dp)
    ) {
        Column(modifier = Modifier.padding(20.dp)) {
            if (onMs == 0L) {
                 Text("No active app usage", color = TextGray, fontSize = 14.sp, modifier = Modifier.fillMaxWidth(), textAlign = TextAlign.Center)
            } else {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                    val formattedOn = formatTime(onMs)
                    val formattedOff = formatTime(offMs.coerceAtLeast(0))
                    Column {
                        Text("Screen ON", color = TextGray, fontSize = 12.sp)
                        Text(formattedOn, color = Color.White, fontSize = 20.sp, fontWeight = FontWeight.Bold)
                    }
                    Column(horizontalAlignment = Alignment.End) {
                        Text("Screen OFF", color = TextGray, fontSize = 12.sp)
                        Text(formattedOff, color = Color.White, fontSize = 20.sp, fontWeight = FontWeight.Bold)
                    }
                }
            }
        }
    }
    Spacer(Modifier.height(36.dp))
}

@Composable
fun OptimizationEngineSection(suggestions: List<OptimizationSuggestion>) {
    val context = LocalContext.current
    var showDialog by remember { mutableStateOf(false) }

    if (showDialog) {
        AlertDialog(
            onDismissRequest = { showDialog = false },
            title = { Text("Optimize Battery Usage", fontWeight = FontWeight.Bold) },
            text = { Text("For better performance, enable system battery optimization settings.") },
            confirmButton = {
                TextButton(onClick = {
                    showDialog = false
                    val batteryIntent = Intent(Settings.ACTION_BATTERY_SAVER_SETTINGS)
                    try {
                        context.startActivity(batteryIntent)
                    } catch (e: Exception) {
                        try {
                            context.startActivity(Intent(Settings.ACTION_SETTINGS))
                        } catch (ex: Exception) {
                            Log.e("EnergyOptimizer", "Could not open settings", ex)
                        }
                    }
                }) {
                    Text("Open Settings", color = CyanAccent)
                }
            },
            dismissButton = {
                TextButton(onClick = {
                    showDialog = false
                    val monitorIntent = Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS)
                    try {
                        context.startActivity(monitorIntent)
                    } catch (e: Exception) {
                        Log.e("EnergyOptimizer", "Could not open monitor settings", e)
                    }
                }) {
                    Text("Allow Monitoring", color = TextGray)
                }
            },
            containerColor = CardColor,
            titleContentColor = Color.White,
            textContentColor = TextGray
        )
    }

    SectionTitle(Icons.Default.FlashOn, "OPTIMIZATION ENGINE")
    Card(
        modifier = Modifier.padding(horizontal = 20.dp).fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = CardColor),
        shape = RoundedCornerShape(14.dp)
    ) {
        Column(modifier = Modifier.padding(20.dp)) {
            suggestions.forEach { suggestion ->
                Row(Modifier.fillMaxWidth().padding(vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                    Box(Modifier.size(8.dp).clip(CircleShape).background(suggestion.color))
                    Spacer(Modifier.width(12.dp))
                    Column(Modifier.weight(1f)) {
                        Text(suggestion.title, color = Color.White, fontSize = 14.sp)
                        Text(suggestion.impact, color = suggestion.color, fontSize = 11.sp, fontWeight = FontWeight.Bold)
                    }
                }
            }
            Spacer(Modifier.height(16.dp))
            Button(
                onClick = { showDialog = true },
                modifier = Modifier.fillMaxWidth(),
                colors = ButtonDefaults.buttonColors(containerColor = CyanAccent),
                shape = RoundedCornerShape(8.dp)
            ) {
                Text("Apply Suggested Optimization", color = DarkBg, fontWeight = FontWeight.Bold)
            }
        }
    }
    Spacer(Modifier.height(36.dp))
}

@Composable
fun PowerProfilesSection(selected: PowerProfile, onSelect: (PowerProfile) -> Unit) {
    SectionTitle(Icons.Default.Settings, "POWER PROFILES")
    Column(modifier = Modifier.padding(horizontal = 20.dp)) {
        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            PowerProfile.entries.forEach { profile ->
                ProfileIcon(profile.icon, profile.label, selected == profile) { onSelect(profile) }
            }
        }
        Spacer(Modifier.height(18.dp))
        Text(selected.statusText, color = TextGray, fontSize = 11.sp, modifier = Modifier.fillMaxWidth(), textAlign = TextAlign.Center)
    }
    Spacer(Modifier.height(36.dp))
}

@Composable
fun ProfileIcon(icon: ImageVector, label: String, selected: Boolean, onClick: () -> Unit) {
    Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.clickable { onClick() }) {
        Box(
            modifier = Modifier.size(54.dp).clip(RoundedCornerShape(14.dp))
                .background(if (selected) CyanAccent.copy(0.12f) else CardColor)
                .border(1.2.dp, if (selected) CyanAccent else Color.Transparent, RoundedCornerShape(14.dp)),
            contentAlignment = Alignment.Center
        ) {
            Icon(icon, null, tint = if (selected) CyanAccent else TextGray, modifier = Modifier.size(26.dp))
        }
        Spacer(Modifier.height(8.dp))
        Text(label, color = if (selected) Color.White else TextGray, fontSize = 10.sp)
    }
}

@Composable
fun InfoCardsSection(maxConsumer: String, drainRate: Float) {
    val healthStatus = when {
        drainRate > 15 -> "High usage detected"
        drainRate > 8 -> "Moderate drain"
        else -> "Optimal"
    }
    Row(modifier = Modifier.padding(horizontal = 20.dp).fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(14.dp)) {
        InfoCard(Modifier.weight(1f), Icons.Default.Whatshot, "Max Consumer", maxConsumer)
        InfoCard(Modifier.weight(1f), Icons.Default.Shield, "System Status", healthStatus, "Based on current load")
    }
    Spacer(Modifier.height(36.dp))
}

@Composable
fun InfoCard(modifier: Modifier, icon: ImageVector, title: String, value: String, subValue: String? = null) {
    Card(modifier = modifier, colors = CardDefaults.cardColors(containerColor = CardColor), shape = RoundedCornerShape(14.dp)) {
        Column(modifier = Modifier.padding(18.dp)) {
            Icon(icon, null, tint = CyanAccent, modifier = Modifier.size(18.dp))
            Spacer(Modifier.height(10.dp))
            Text(title, color = TextGray, fontSize = 11.sp)
            Text(value, color = Color.White, fontSize = 16.sp, fontWeight = FontWeight.Bold, maxLines = 2, overflow = TextOverflow.Ellipsis)
            if (subValue != null) {
                Spacer(Modifier.height(4.dp))
                Text(subValue, color = TextGray.copy(0.7f), fontSize = 10.sp)
            }
        }
    }
}

@Composable
fun DailyUsageSection(weeklyMs: List<Long>, todayMs: Long, isInitializing: Boolean) {
    val maxVal = (weeklyMs.maxOrNull() ?: 1L).coerceAtLeast(18000000L) // 5h scale
    
    Column(modifier = Modifier.padding(horizontal = 20.dp)) {
        Text("Weekly Usage Trends", color = Color.White, fontSize = 20.sp, fontWeight = FontWeight.Bold)
        Spacer(Modifier.height(12.dp))
        Card(modifier = Modifier.fillMaxWidth(), colors = CardDefaults.cardColors(containerColor = CardColor), shape = RoundedCornerShape(16.dp)) {
            Column(modifier = Modifier.padding(20.dp)) {
                if (weeklyMs.all { it == 0L } && todayMs == 0L) {
                    Text("No active app usage", color = TextGray, fontSize = 12.sp, modifier = Modifier.fillMaxWidth(), textAlign = TextAlign.Center)
                } else {
                    Box(modifier = Modifier.fillMaxWidth().height(140.dp)) {
                        Row(modifier = Modifier.fillMaxSize(), horizontalArrangement = Arrangement.SpaceEvenly, verticalAlignment = Alignment.Bottom) {
                            val days = listOf("S", "M", "T", "W", "T", "F", "S")
                            repeat(7) { i ->
                                val ms = if (i < weeklyMs.size) weeklyMs[i] else 0L
                                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                                    Box(modifier = Modifier.width(26.dp).fillMaxHeight((ms.toFloat()/maxVal).coerceAtLeast(0.05f)).clip(RoundedCornerShape(4.dp)).background(if(i == weeklyMs.size - 1) CyanAccent else TextGray.copy(0.2f)))
                                    Spacer(Modifier.height(8.dp))
                                    Text(days[i], color = TextGray, fontSize = 10.sp)
                                }
                            }
                        }
                    }
                }
            }
        }
    }
    Spacer(Modifier.height(36.dp))
}

@Composable
fun AppUsageListSection(apps: List<AppUsageInfo>, isInitializing: Boolean, totalUsage: Long) {
    Column(modifier = Modifier.padding(horizontal = 20.dp)) {
        Text("Battery Usage by App", color = Color.White, fontSize = 20.sp, fontWeight = FontWeight.Bold)
        Spacer(Modifier.height(4.dp))
        Text("Estimated based on usage patterns", color = TextGray, fontSize = 10.sp)
        Spacer(Modifier.height(14.dp))
        Card(modifier = Modifier.fillMaxWidth(), colors = CardDefaults.cardColors(containerColor = CardColor), shape = RoundedCornerShape(14.dp)) {
            if (apps.isEmpty()) {
                Box(Modifier.fillMaxWidth().height(100.dp), contentAlignment = Alignment.Center) {
                    Text("No active app usage", color = TextGray, fontSize = 14.sp)
                }
            } else {
                Column(modifier = Modifier.padding(vertical = 10.dp)) {
                    apps.take(8).forEach { app -> AppRow(app) }
                }
            }
        }
    }
    Spacer(Modifier.height(36.dp))
}

@Composable
fun AppRow(app: AppUsageInfo) {
    val context = LocalContext.current
    Row(Modifier.padding(horizontal = 18.dp, vertical = 12.dp), verticalAlignment = Alignment.CenterVertically) {
        val icon = remember(app.pkg) { getAppIcon(context, app.pkg) }
        Box(Modifier.size(36.dp).clip(RoundedCornerShape(8.dp)).background(Color.White.copy(0.04f)), contentAlignment = Alignment.Center) {
            if (icon != null) Image(bitmap = icon.asImageBitmap(), null, Modifier.fillMaxSize())
            else Icon(Icons.Default.Android, null, tint = TextGray)
        }
        Spacer(Modifier.width(14.dp))
        Column(Modifier.weight(1f)) {
            Text(app.name, color = Color.White, fontSize = 14.sp, fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text("On screen: ${formatTime(app.screenTimeMs)}", color = TextGray, fontSize = 12.sp)
            if (app.backgroundTimeMs > 0) {
                Text("Background: ${formatTime(app.backgroundTimeMs)}", color = TextGray, fontSize = 12.sp)
            }
        }
        Text(app.batteryPercent, color = Color.White, fontSize = 14.sp, fontWeight = FontWeight.Bold)
    }
}

@Composable
fun HistoryLogSection(todayMs: Long, yesterdayMs: Long) {
    Column(modifier = Modifier.padding(horizontal = 20.dp)) {
        SectionTitle(Icons.Default.History, "DAILY COMPARISON")
        Card(modifier = Modifier.fillMaxWidth(), colors = CardDefaults.cardColors(containerColor = CardColor), shape = RoundedCornerShape(14.dp)) {
            Column(Modifier.padding(20.dp)) {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                    Column {
                        Text("Today", color = TextGray, fontSize = 11.sp)
                        Text(formatTime(todayMs), color = CyanAccent, fontSize = 18.sp, fontWeight = FontWeight.Bold)
                    }
                    Column(horizontalAlignment = Alignment.End) {
                        Text("Yesterday", color = TextGray, fontSize = 11.sp)
                        Text(formatTime(yesterdayMs), color = Color.White, fontSize = 18.sp, fontWeight = FontWeight.Bold)
                    }
                }
                Spacer(Modifier.height(12.dp))
                val diff = (todayMs - yesterdayMs) / 60000
                val diffText = if (diff > 0) "+$diff min vs yesterday" else "$diff min vs yesterday"
                Text(diffText, color = if (diff > 0) WarningOrange else SuccessGreen, fontSize = 12.sp, fontWeight = FontWeight.Bold)
            }
        }
    }
}

@Composable
fun SectionTitle(icon: ImageVector, title: String) {
    Row(modifier = Modifier.padding(horizontal = 20.dp, vertical = 12.dp), verticalAlignment = Alignment.CenterVertically) {
        Icon(icon, null, tint = TextGray, modifier = Modifier.size(16.dp))
        Spacer(Modifier.width(8.dp))
        Text(title, color = TextGray, fontSize = 10.sp, fontWeight = FontWeight.Bold, letterSpacing = 0.5.sp)
    }
}

fun formatTime(ms: Long): String {
    val h = ms / 3600000
    val m = (ms % 3600000) / 60000
    val s = (ms % 60000) / 1000
    return when {
        h > 0 -> "${h}h ${m}m"
        m > 0 -> "${m}m ${s}s"
        else -> "${s}s"
    }
}

fun getStartOfDay(): Long = Calendar.getInstance().apply {
    set(Calendar.HOUR_OF_DAY, 0); set(Calendar.MINUTE, 0); set(Calendar.SECOND, 0); set(Calendar.MILLISECOND, 0)
}.timeInMillis

fun isUserApp(context: Context, packageName: String): Boolean {
    val ignoredPrefixes = listOf(
        "com.android",
        "com.miui",
        "com.google.android.gms",
        "com.android.systemui",
        "com.mi.android",
        "com.qualcomm",
        "android"
    )
    if (ignoredPrefixes.any { packageName.startsWith(it) }) return false
    if (packageName == context.packageName) return false
    return true
}

fun getAppLabel(context: Context, pkg: String): String = try {
    val pm = context.packageManager
    val appInfo = pm.getApplicationInfo(pkg, 0)
    pm.getApplicationLabel(appInfo).toString()
} catch (e: Exception) { pkg }

fun getAppIcon(context: Context, pkg: String): Bitmap? = try {
    val pm = context.packageManager
    val drawable = pm.getApplicationIcon(pkg)
    val bitmap = Bitmap.createBitmap(drawable.intrinsicWidth.coerceAtLeast(1), drawable.intrinsicHeight.coerceAtLeast(1), Bitmap.Config.ARGB_8888)
    val canvas = Canvas(bitmap); drawable.setBounds(0, 0, canvas.width, canvas.height); drawable.draw(canvas); bitmap
} catch (e: Exception) { null }

fun hasUsagePermission(context: Context): Boolean {
    val appOps = context.getSystemService(Context.APP_OPS_SERVICE) as AppOpsManager
    val mode = appOps.checkOpNoThrow(
        AppOpsManager.OPSTR_GET_USAGE_STATS,
        android.os.Process.myUid(),
        context.packageName
    )
    return mode == AppOpsManager.MODE_ALLOWED
}

fun generateInsight(
    usageMap: Map<String, Long>,
    batteryDrain: Float,
    todayUsage: Long,
    yesterdayUsage: Long
): String {

    if (usageMap.isEmpty()) {
        return "Collecting usage data..."
    }

    val topApp = usageMap.maxByOrNull { it.value }?.key

    // CASE 1: No drain yet
    if (batteryDrain < 1f) {
        return "Battery usage is currently stable"
    }

    // CASE 2: Top consumer
    if (topApp != null) {
        return "$topApp is the highest battery consumer today"
    }

    // CASE 3: Comparison
    val diff = todayUsage - yesterdayUsage

    return when {
        diff > 60000 -> "Usage increased compared to yesterday"
        diff < -60000 -> "Usage reduced compared to yesterday"
        else -> "Usage is consistent with yesterday"
    }
}
