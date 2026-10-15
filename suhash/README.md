# 🔋 Energy Optimizer Pro

### *Precision Battery Intelligence & Real-Time Energy Management for Android*

**Energy Optimizer Pro** is a sophisticated Android utility designed to give users complete transparency over their device’s power consumption. Built with modern Android standards, it provides high-fidelity battery telemetry, granular per-app energy tracking, and intelligent power profiling to help users understand and extend their battery longevity.

---

## 🚀 Key Features

### 📊 **Live Battery Telemetry**
- **Dynamic Graphing:** Visualizes battery level fluctuations over the last 60 minutes.
- **Real-Time Sampling:** Captures precise battery snapshots every minute using the `BatteryManager` API.
- **Visual Feedback:** A custom-drawn circular indicator showing charge percentage with neon-style sweep gradients.

### 📱 **Granular App Usage Tracking**
- **Foreground Monitoring:** Uses `UsageStatsManager` to track exactly how long user-installed apps stay on screen.
- **Live Updates:** Unlike standard system settings, the app usage timers update in real-time (every 5 seconds) while the app is being used.
- **Hybrid Attribution Logic:** Correlates app screen time with actual battery percentage drops to estimate the "cost" of each app.

### ⚡ **Intelligent Power Profiles**
- Five distinct monitoring modes to balance accuracy with its own energy footprint:
  - **Performance:** 2s polling for high-precision debugging.
  - **Balanced:** 5s polling for daily use.
  - **Power Saver / Night:** 15-30s polling to minimize app impact.
  - **Student:** 10s polling with focus-active monitoring.

### ⚠️ **Smart Drain Alerts**
- **Proactive Notifications:** Detects "High Drain" events (unusual drops >15%/hour) and alerts the user via system notifications.
- **Low Battery Prediction:** Provides time-remaining estimates based on current usage patterns.

### 📈 **Historical Trends & Insights**
- **Daily Comparison:** Shows how much more (or less) you used your phone today compared to yesterday.
- **Weekly Analytics:** A 7-day bar chart visualizing total usage trends.
- **System Insights:** AI-driven natural language summaries explaining current system health.

---

## 🛠️ Tech Stack

- **UI Framework:** [Jetpack Compose](https://developer.android.com/compose) (100% Declarative UI)
- **Language:** [Kotlin](https://kotlinlang.org/)
- **Background Processing:** [Foreground Services](https://developer.android.com/guide/components/foreground-services) (Android 14+ `specialUse` type)
- **Data Visualization:** [Canvas API](https://developer.android.com/reference/kotlin/androidx/compose/ui/graphics/drawscope/DrawScope) (Custom path and graph drawing)
- **Storage:** [SharedPreferences](https://developer.android.com/training/data-storage/shared-preferences) (Optimized session & history mapping)
- **Core APIs:** `UsageStatsManager`, `BatteryManager`, `PowerManager`, `BroadcastReceiver`

---

## ⚙️ How It Works (Technical Workflow)

1. **Initialization:** On launch, the app verifies **Usage Stats** and **Battery Optimization** permissions.
2. **Monitoring Engine:** The `UsageTrackingService` starts in the foreground. It queries `UsageEvents` to detect when a user switches apps.
3. **Data Aggregation:** 
   - **Live Data:** Calculated by `System.currentTimeMillis() - sessionStartTime`.
   - **Stored Data:** Persistent session logs stored in a `Map<String, Long>` structure.
4. **Processing:** The app filters out system-level packages (like `com.android.systemui`) to focus only on user-installed applications.
5. **Visualization:** The `MainActivity` observes the data stream and renders it using Jetpack Compose's state-driven UI.

---

## 📂 Project Structure

```text
app/src/main/java/com/example/energyoptimizer/
├── MainActivity.kt          # UI layer, state management, and permission flows.
├── UsageTrackingService.kt  # Background engine for polling events & battery levels.
├── BootReceiver.kt          # Restarts monitoring automatically on device reboot.
└── ui/theme/                # Custom Material3 theme and neon color palette.
```

---

## 🛠️ Installation & Setup

1. **Clone the Repository:**
   ```bash
   git clone https://github.com/YOUR_USERNAME/EnergyOptimizerPro.git
   ```
2. **Open in Android Studio:**
   - Use **Android Studio Ladybug (2024.2.1)** or newer.
   - Recommended SDK: **API 34 (Android 14)**.
3. **Permissions Required:**
   - Upon first launch, the app will request **Usage Access**. This is required to see which apps are running.
   - It will also request to **Ignore Battery Optimizations** so the tracking service isn't killed by the OS.

---

## 👥 Team Contributions

*   **Member 1:** Lead UI/UX Developer (Jetpack Compose, Canvas Graphics, Animations).
*   **Member 2:** Backend & Service Architect (Foreground Services, UsageStats Integration, System Receivers).
*   **Member 3:** Data & Logic Engineer (Optimization Algorithms, History Logs, Permissions, Notification System).

---

## 📄 License

This project is licensed under the MIT License - see the [LICENSE](LICENSE) file for details.

---
*Developed with ❤️ for the Android Community.*
