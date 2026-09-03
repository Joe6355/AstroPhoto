package com.joe6355.astrophoto

import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.BatteryManager
import android.os.Build
import android.os.PowerManager

class SeriesSafetyChecker(
    private val context: Context,
    private val storageChecker: StorageSpaceChecker
) {
    suspend fun blockingReason(): String? {
        val storage = storageChecker.readAvailableSpace()
        if (storage.criticallyLow) {
            return "Серия остановлена: свободно меньше ${formatStorageSize(StorageSpaceInfo.CRITICAL_BYTES)}"
        }

        val battery = context.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
        if (battery != null) {
            val level = battery.getIntExtra(BatteryManager.EXTRA_LEVEL, -1)
            val scale = battery.getIntExtra(BatteryManager.EXTRA_SCALE, -1)
            val status = battery.getIntExtra(BatteryManager.EXTRA_STATUS, -1)
            val charging = status == BatteryManager.BATTERY_STATUS_CHARGING ||
                status == BatteryManager.BATTERY_STATUS_FULL
            val percent = if (level >= 0 && scale > 0) level * 100 / scale else null
            if (!charging && percent != null && percent <= MIN_BATTERY_PERCENT) {
                return "Серия остановлена: заряд $percent%"
            }
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            val thermalStatus = context.getSystemService(PowerManager::class.java)
                ?.currentThermalStatus
            if (thermalStatus != null && thermalStatus >= PowerManager.THERMAL_STATUS_SEVERE) {
                return "Серия остановлена: телефон перегрелся"
            }
        }
        return null
    }

    private companion object {
        const val MIN_BATTERY_PERCENT = 5
    }
}
