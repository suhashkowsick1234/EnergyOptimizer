package com.example.energyoptimizer

import android.app.*
import android.app.usage.UsageEvents
import android.app.usage.UsageStatsManager
import android.content.*
import android.content.pm.ServiceInfo
import android.os.BatteryManager
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.Process
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.content.edit
import java.text.SimpleDateFormat
import java.util.*

class UsageTrackingService : Service() {

    private val CHANNEL_ID = "energy_monitor_v2"
    private val ALERT_CHANNEL_ID = "energy_alerts"
    private val NOTIFICATION_ID = 202
    private val ALERT_NOTIFICATION_ID = 203
    
    private val handler = Handler(Looper.getMainLooper())
    private var lastBatteryLevel = -1
    private var lastLevelTimestamp = 0L

    private var currentAppPackage: String? = null
    private var sessionStartTime: Long = 0L
    private var lastProcessedTimestamp: Long = 0L
    private var pollingInterval: Long = 5000L // Default Balanced

    private val screenReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            if (intent.action == Intent.ACTION_SCREEN_OFF) {
                finalizeCurrentSession()
            }
        }
    }

    private val dataRunnable = object : Runnable {
        override fun run() {
            if (hasUsagePermission()) {
                processUsageEvents()
            }
            checkBatteryHistory()
            handler.postDelayed(this, pollingInterval) 
        }
    }

    private val batteryReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            val level = intent.getIntExtra(BatteryManager.EXTRA_LEVEL, -1)
            val scale = intent.getIntExtra(BatteryManager.EXTRA_SCALE, -1)
            if (level != -1 && scale != -1) {
                val batteryPct = (level * 100f / scale).toInt()
                handleBatteryAlerts(batteryPct)
                
                val prefs = getSharedPreferences("usage_tracking_v15", Context.MODE_PRIVATE)
                if (prefs.getInt("day_start_level", -1) == -1) {
                    prefs.edit { putInt("day_start_level", batteryPct) }
                }
            }
        }
    }

    override fun onCreate() {
        super.onCreate()
        Log.d("USAGE_FIX", "UsageTrackingService created")
        startForegroundService()
        
        val prefs = getSharedPreferences("usage_tracking_v15", Context.MODE_PRIVATE)
        lastProcessedTimestamp = prefs.getLong("last_processed_ts", getStartOfDay())
        currentAppPackage = prefs.getString("active_pkg", null)
        sessionStartTime = prefs.getLong("active_start", 0L)
        
        registerReceiver(batteryReceiver, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
        registerReceiver(screenReceiver, IntentFilter(Intent.ACTION_SCREEN_OFF))
        
        handler.post(dataRunnable)
    }

    private fun startForegroundService() {
        val manager = getSystemService(NotificationManager::class.java)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(CHANNEL_ID, "Energy Tracking", NotificationManager.IMPORTANCE_MIN)
            manager?.createNotificationChannel(channel)
            val alertChannel = NotificationChannel(ALERT_CHANNEL_ID, "Energy Alerts", NotificationManager.IMPORTANCE_HIGH)
            manager?.createNotificationChannel(alertChannel)
        }

        val notification = NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("Energy Optimizer Pro")
            .setContentText("Monitoring system energy usage")
            .setSmallIcon(android.R.drawable.ic_menu_info_details)
            .setPriority(NotificationCompat.PRIORITY_MIN)
            .setOngoing(true)
            .build()

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            startForeground(NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
        } else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            @Suppress("DEPRECATION")
            startForeground(NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_NONE)
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }
    }

    private fun handleBatteryAlerts(level: Int) {
        val now = System.currentTimeMillis()
        if (lastBatteryLevel == -1) {
            lastBatteryLevel = level
            lastLevelTimestamp = now
            return
        }

        if (level < lastBatteryLevel) {
            val drop = lastBatteryLevel - level
            val durationMs = now - lastLevelTimestamp
            if (durationMs > 600000) {
                val drainRate = (drop.toFloat() / (durationMs / 3600000f))
                if (drainRate > 15.0) {
                    sendAlert("High Drain Detected", "System analysis indicates unusual battery consumption.")
                }
            }
        }
        
        if (level <= 20 && lastBatteryLevel > 20) {
            sendAlert("Low Battery", "Estimated 2 hours remaining based on current usage")
        }

        if (level != lastBatteryLevel) {
            lastBatteryLevel = level
            lastLevelTimestamp = now
        }
    }

    private fun checkBatteryHistory() {
        val uiPrefs = getSharedPreferences("ui_state_v1", Context.MODE_PRIVATE)
        val now = System.currentTimeMillis()
        val lastHistoryTs = uiPrefs.getLong("last_history_ts", 0L)
        
        if (now - lastHistoryTs >= 60000) {
            val bm = getSystemService(Context.BATTERY_SERVICE) as BatteryManager
            val level = bm.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY)
            
            val historyStr = uiPrefs.getString("battery_history", "") ?: ""
            val history = if (historyStr.isEmpty()) mutableListOf<String>() else historyStr.split(",").toMutableList()
            history.add(level.toString())
            if (history.size > 60) history.removeAt(0)
            
            uiPrefs.edit {
                putString("battery_history", history.joinToString(","))
                putLong("last_history_ts", now)
            }
        }
    }

    private fun sendAlert(title: String, message: String) {
        val manager = getSystemService(NotificationManager::class.java)
        val notification = NotificationCompat.Builder(this, ALERT_CHANNEL_ID)
            .setContentTitle(title)
            .setContentText(message)
            .setSmallIcon(android.R.drawable.stat_sys_warning)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setAutoCancel(true)
            .build()
        manager?.notify(ALERT_NOTIFICATION_ID, notification)
    }

    private fun isUserApp(packageName: String): Boolean {
        val ignoredPrefixes = listOf(
            "com.android", "com.miui", "com.google.android.gms",
            "com.android.systemui", "com.mi.android", "com.qualcomm", "android"
        )
        if (ignoredPrefixes.any { packageName.startsWith(it) }) return false
        if (packageName == this.packageName) return false
        return true
    }

    private fun finalizeCurrentSession() {
        val now = System.currentTimeMillis()
        if (currentAppPackage != null && sessionStartTime > 0) {
            val duration = now - sessionStartTime
            if (duration >= 1000) {
                saveSession(currentAppPackage!!, duration)
                Log.d("USAGE_FIX", "Finalized session: $currentAppPackage duration=$duration")
            }
            currentAppPackage = null
            sessionStartTime = 0L
            
            val prefs = getSharedPreferences("usage_tracking_v15", Context.MODE_PRIVATE)
            prefs.edit {
                remove("active_pkg")
                remove("active_start")
            }
        }
    }

    private fun saveSession(pkg: String, duration: Long) {
        if (!isUserApp(pkg)) return
        val prefs = getSharedPreferences("usage_tracking_v15", Context.MODE_PRIVATE)
        
        // Clean storage: directly maintain Map<packageName, totalDuration>
        val usageData = prefs.getStringSet("today_usage_map", mutableSetOf())?.toMutableSet() ?: mutableSetOf()
        val entry = usageData.find { it.startsWith("$pkg:") }
        
        if (entry != null) {
            val current = entry.split(":")[1].toLong()
            usageData.remove(entry)
            usageData.add("$pkg:${current + duration}")
        } else {
            usageData.add("$pkg:$duration")
        }
        
        prefs.edit { 
            putStringSet("today_usage_map", usageData) 
        }
        updateWeeklyTotals(prefs)
    }

    private fun processUsageEvents() {
        val prefs = getSharedPreferences("usage_tracking_v15", Context.MODE_PRIVATE)
        val now = System.currentTimeMillis()
        val startOfDay = getStartOfDay()
        
        val todayStr = SimpleDateFormat("yyyyMMdd", Locale.US).format(Date())
        val lastDay = prefs.getString("current_day_str", "")
        if (lastDay != "" && lastDay != todayStr) {
            finalizeCurrentSession()
            val lastUsage = prefs.getStringSet("today_usage_map", emptySet())
            prefs.edit {
                putStringSet("yesterday_usage_map", lastUsage)
                remove("today_usage_map")
                putString("current_day_str", todayStr)
                remove("day_start_level")
                putLong("last_processed_ts", startOfDay)
            }
            lastProcessedTimestamp = startOfDay
        } else if (lastDay == "") {
            prefs.edit { putString("current_day_str", todayStr) }
        }

        val usm = getSystemService(Context.USAGE_STATS_SERVICE) as UsageStatsManager
        val events = usm.queryEvents(lastProcessedTimestamp, now)
        val event = UsageEvents.Event()

        while (events.hasNextEvent()) {
            events.getNextEvent(event)
            if (event.eventType == UsageEvents.Event.ACTIVITY_RESUMED) {
                val newPackage = event.packageName
                val ts = event.timeStamp

                if (newPackage != currentAppPackage) {
                    if (currentAppPackage != null && sessionStartTime > 0) {
                        val duration = ts - sessionStartTime
                        if (duration >= 1000) {
                            saveSession(currentAppPackage!!, duration)
                        }
                    }
                    currentAppPackage = newPackage
                    sessionStartTime = ts
                }
            }
        }

        lastProcessedTimestamp = now
        prefs.edit { 
            putLong("last_processed_ts", now)
            putString("active_pkg", currentAppPackage)
            putLong("active_start", sessionStartTime)
        }
    }

    private fun updateWeeklyTotals(prefs: SharedPreferences) {
        val todayStr = SimpleDateFormat("yyyyMMdd", Locale.US).format(Date())
        val usageData = prefs.getStringSet("today_usage_map", emptySet()) ?: emptySet()
        val totalMs = usageData.sumOf { it.split(":")[1].toLong() }
        
        val weeklyData = prefs.getString("weekly_totals", "") ?: ""
        val daysMap = weeklyData.split("|").filter { it.isNotEmpty() }.map { 
            val p = it.split(":")
            if (p.size == 2) p[0] to p[1].toLong() else "" to 0L
        }.filter { it.first != "" }.toMap().toMutableMap()
        
        daysMap[todayStr] = totalMs
        val sortedKeys = daysMap.keys.sorted().takeLast(7)
        val newWeeklyData = sortedKeys.joinToString("|") { "$it:${daysMap[it]}" }
        prefs.edit { putString("weekly_totals", newWeeklyData) }
    }

    private fun getStartOfDay(): Long = Calendar.getInstance().apply {
        set(Calendar.HOUR_OF_DAY, 0); set(Calendar.MINUTE, 0); set(Calendar.SECOND, 0); set(Calendar.MILLISECOND, 0)
    }.timeInMillis

    private fun hasUsagePermission(): Boolean {
        val appOps = getSystemService(Context.APP_OPS_SERVICE) as AppOpsManager
        val mode = appOps.checkOpNoThrow(AppOpsManager.OPSTR_GET_USAGE_STATS, Process.myUid(), packageName)
        return mode == AppOpsManager.MODE_ALLOWED
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val profileName = intent?.getStringExtra("profile_name")
        if (profileName != null) {
            pollingInterval = when (profileName) {
                "PERFORMANCE" -> 3000L
                "BALANCED" -> 5000L
                "POWER_SAVER" -> 15000L
                "NIGHT" -> 20000L
                "STUDENT" -> 8000L
                else -> 5000L
            }
            Log.d("USAGE_FIX", "Interval updated: $pollingInterval ms")
        }
        return START_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        finalizeCurrentSession()
        unregisterReceiver(batteryReceiver)
        unregisterReceiver(screenReceiver)
        handler.removeCallbacks(dataRunnable)
        super.onDestroy()
    }
}
