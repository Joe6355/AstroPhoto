package com.example.astrophoto

import android.Manifest
import android.app.Activity
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.core.content.edit
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

data class SessionProcessingState(
    val sessionFolder: String,
    val label: String,
    val status: String,
    val current: Int,
    val total: Int,
    val running: Boolean
)

data class InterruptedSessionProcessing(
    val sessionFolder: String,
    val label: String
)

/** Keeps user-started processing alive while its Compose screen is not visible. */
object SessionProcessingCoordinator {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val lock = Any()
    private val jobs = mutableMapOf<String, Job>()
    private val mutableStates = MutableStateFlow<Map<String, SessionProcessingState>>(emptyMap())

    val states: StateFlow<Map<String, SessionProcessingState>> = mutableStates.asStateFlow()

    fun isActive(sessionFolder: String): Boolean = synchronized(lock) {
        jobs[sessionFolder]?.isActive == true
    }

    fun start(
        context: Context,
        sessionFolder: String,
        label: String,
        initialStatus: String,
        initialTotal: Int,
        block: suspend () -> Unit
    ): Job? = synchronized(lock) {
        if (jobs[sessionFolder]?.isActive == true) return null

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            ContextCompat.checkSelfPermission(
                context,
                Manifest.permission.POST_NOTIFICATIONS
            ) != PackageManager.PERMISSION_GRANTED
        ) {
            (context as? Activity)?.let { activity ->
                ActivityCompat.requestPermissions(
                    activity,
                    arrayOf(Manifest.permission.POST_NOTIFICATIONS),
                    NOTIFICATION_PERMISSION_REQUEST
                )
            }
        }

        updateState(
            SessionProcessingState(
                sessionFolder = sessionFolder,
                label = label,
                status = initialStatus,
                current = 0,
                total = initialTotal,
                running = true
            )
        )
        ProcessingRecoveryStore(context.applicationContext).markStarted(sessionFolder, label)
        ContextCompat.startForegroundService(
            context.applicationContext,
            Intent(context.applicationContext, ProcessingForegroundService::class.java)
                .setAction(ProcessingForegroundService.ACTION_START)
        )

        scope.launch {
            try {
                block()
            } catch (error: CancellationException) {
                update(sessionFolder, "Обработка остановлена")
                throw error
            } finally {
                synchronized(lock) {
                    jobs.remove(sessionFolder)
                    val previous = mutableStates.value[sessionFolder]
                    if (previous != null) updateState(previous.copy(running = false))
                    ProcessingRecoveryStore(context.applicationContext).markFinished(sessionFolder)
                    if (jobs.values.none { it.isActive }) {
                        context.applicationContext.stopService(
                            Intent(context.applicationContext, ProcessingForegroundService::class.java)
                        )
                    }
                }
            }
        }.also { jobs[sessionFolder] = it }
    }

    fun update(
        sessionFolder: String,
        status: String,
        current: Int? = null,
        total: Int? = null
    ) {
        synchronized(lock) {
            val previous = mutableStates.value[sessionFolder] ?: return
            updateState(
                previous.copy(
                    status = status,
                    current = current ?: previous.current,
                    total = total ?: previous.total
                )
            )
        }
    }

    fun cancel(sessionFolder: String) {
        synchronized(lock) { jobs[sessionFolder] }?.cancel()
    }

    fun cancelAll() {
        synchronized(lock) { jobs.values.toList() }.forEach(Job::cancel)
    }

    private fun updateState(state: SessionProcessingState) {
        mutableStates.value = mutableStates.value + (state.sessionFolder to state)
    }

    private const val NOTIFICATION_PERMISSION_REQUEST = 4102
}

class ProcessingRecoveryStore(context: Context) {
    private val preferences = context.getSharedPreferences(PREFERENCES, Context.MODE_PRIVATE)

    fun markStarted(sessionFolder: String, label: String) {
        preferences.edit {
                putString(KEY_SESSION, sessionFolder)
                .putString(KEY_LABEL, label)
            }
    }

    fun markFinished(sessionFolder: String) {
        if (preferences.getString(KEY_SESSION, null) == sessionFolder) {
            preferences.edit {clear()}
        }
    }

    fun takeInterrupted(): InterruptedSessionProcessing? {
        val sessionFolder = preferences.getString(KEY_SESSION, null) ?: return null
        if (SessionProcessingCoordinator.isActive(sessionFolder)) return null
        val label = preferences.getString(KEY_LABEL, null) ?: "Обработка"
        preferences.edit {clear()}
        return InterruptedSessionProcessing(sessionFolder, label)
    }

    private companion object {
        const val PREFERENCES = "processing_recovery"
        const val KEY_SESSION = "session_folder"
        const val KEY_LABEL = "label"
    }
}
