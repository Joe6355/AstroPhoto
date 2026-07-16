package com.example.astrophoto.processing.jpeg.v2.diagnostics

import android.app.ActivityManager
import android.app.ApplicationExitInfo
import android.content.Context
import android.os.Build

enum class PreviousProcessExitKind(val displayName: String) {
    LOW_MEMORY("нехватка памяти"),
    JAVA_CRASH("сбой Java"),
    NATIVE_CRASH("нативный сбой"),
    ANR("приложение не отвечало"),
    USER_REQUESTED("остановлено пользователем"),
    SYSTEM_KILL("остановлено системой"),
    UNKNOWN("неизвестная остановка процесса")
}

data class PreviousProcessExit(
    val kind: PreviousProcessExitKind,
    val timestampMillis: Long,
    val reasonCode: Int,
    val description: String?
)

class PreviousProcessExitReader(private val context: Context) {
    fun latest(sinceMillis: Long = 0L): PreviousProcessExit? {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) return null
        val manager = context.getSystemService(ActivityManager::class.java) ?: return null
        return runCatching {
            manager.getHistoricalProcessExitReasons(context.packageName, 0, MAX_REASONS)
                .asSequence()
                .filter { it.timestamp >= sinceMillis }
                .maxByOrNull { it.timestamp }
                ?.let { info ->
                    PreviousProcessExit(
                        kind = classify(info.reason),
                        timestampMillis = info.timestamp,
                        reasonCode = info.reason,
                        description = info.description?.toString()?.take(MAX_DESCRIPTION_LENGTH)
                    )
                }
        }.getOrNull()
    }

    companion object {
        private const val MAX_REASONS = 8
        private const val MAX_DESCRIPTION_LENGTH = 160

        fun classify(reason: Int): PreviousProcessExitKind = when (reason) {
            ApplicationExitInfo.REASON_LOW_MEMORY,
            ApplicationExitInfo.REASON_EXCESSIVE_RESOURCE_USAGE -> PreviousProcessExitKind.LOW_MEMORY
            ApplicationExitInfo.REASON_CRASH -> PreviousProcessExitKind.JAVA_CRASH
            ApplicationExitInfo.REASON_CRASH_NATIVE -> PreviousProcessExitKind.NATIVE_CRASH
            ApplicationExitInfo.REASON_ANR -> PreviousProcessExitKind.ANR
            ApplicationExitInfo.REASON_USER_REQUESTED -> PreviousProcessExitKind.USER_REQUESTED
            ApplicationExitInfo.REASON_DEPENDENCY_DIED,
            ApplicationExitInfo.REASON_INITIALIZATION_FAILURE,
            ApplicationExitInfo.REASON_PERMISSION_CHANGE,
            ApplicationExitInfo.REASON_SIGNALED,
            ApplicationExitInfo.REASON_OTHER -> PreviousProcessExitKind.SYSTEM_KILL
            ApplicationExitInfo.REASON_UNKNOWN -> PreviousProcessExitKind.UNKNOWN
            else -> PreviousProcessExitKind.UNKNOWN
        }
    }
}
