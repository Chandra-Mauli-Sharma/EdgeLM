package ai.edgelm.service

import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.BatteryManager
import android.os.Build
import android.os.PowerManager

/**
 * Battery + thermal governor for the scheduler (arch doc Part 8: "governors sit above
 * the executors as hard clamps"). Translates live device state into a scheduling
 * policy: under heat or low battery, background/batch inference is deferred so the
 * device stays cool and the foreground app still gets served.
 *
 * Read-only and cheap — the scheduler queries [snapshot] at admission time. Pure
 * platform APIs; no native code.
 */
class DeviceGovernor(private val appContext: Context) {

    /** Thermal pressure, coarsened from PowerManager's 0..6 scale into what we act on. */
    enum class Thermal { NORMAL, WARN, SEVERE }

    /** A cheap read of the current device state used to clamp scheduling. */
    data class State(
        val thermal: Thermal,
        val onBattery: Boolean,
        val batteryLow: Boolean,     // <= 20% or system "low" broadcast
        val powerSaveMode: Boolean,
    ) {
        /** Should we defer non-interactive (BATCH/BACKGROUND) work right now? */
        val deferBackground: Boolean
            get() = thermal != Thermal.NORMAL || powerSaveMode || batteryLow

        /** Should we refuse even to admit background work (hard clamp)? */
        val blockBackground: Boolean
            get() = thermal == Thermal.SEVERE
    }

    private val powerManager by lazy { appContext.getSystemService(PowerManager::class.java) }

    fun snapshot(): State {
        val thermal = readThermal()
        val (onBattery, pct) = readBattery()
        val low = pct in 0..20
        val saver = runCatching { powerManager?.isPowerSaveMode == true }.getOrDefault(false)
        return State(thermal, onBattery, low, saver)
    }

    private fun readThermal(): Thermal {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) return Thermal.NORMAL
        val status = runCatching { powerManager?.currentThermalStatus }.getOrNull()
            ?: PowerManager.THERMAL_STATUS_NONE
        return when {
            status >= PowerManager.THERMAL_STATUS_SEVERE -> Thermal.SEVERE   // 3+
            status >= PowerManager.THERMAL_STATUS_LIGHT -> Thermal.WARN       // 1..2
            else -> Thermal.NORMAL
        }
    }

    /** @return (onBattery, batteryPct) — pct is -1 if unknown. */
    private fun readBattery(): Pair<Boolean, Int> {
        val intent: Intent? = runCatching {
            appContext.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
        }.getOrNull() ?: return true to -1
        val status = intent!!.getIntExtra(BatteryManager.EXTRA_STATUS, -1)
        val charging = status == BatteryManager.BATTERY_STATUS_CHARGING ||
            status == BatteryManager.BATTERY_STATUS_FULL
        val level = intent.getIntExtra(BatteryManager.EXTRA_LEVEL, -1)
        val scale = intent.getIntExtra(BatteryManager.EXTRA_SCALE, -1)
        val pct = if (level >= 0 && scale > 0) level * 100 / scale else -1
        return (!charging) to pct
    }
}
