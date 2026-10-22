package com.joe6355.astrophoto

import android.Manifest
import android.app.Activity
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.SystemClock
import android.util.Log
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import org.json.JSONObject

data class SessionProcessingState(
    val sessionFolder: String,
    val label: String,
    val status: String,
    val current: Int,
    val total: Int,
    val running: Boolean,
    val stageEtaMillis: Long? = null
) {
    val statusWithEta: String get() = stageEtaMillis?.takeIf { running }?.let {
        "$status\nДо конца этапа ≈ ${formatSeriesDuration(it)}"
    } ?: status
}

data class InterruptedSessionProcessing(
    val sessionFolder: String,
    val label: String
)

/** Keeps user-started processing alive while its Compose screen is not visible. */
object SessionProcessingCoordinator {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val lock = Any()
    private val jobs = mutableMapOf<String, Job>()
    private val etaEstimators = mutableMapOf<String, ProcessingEtaEstimator>()
    private val mutableStates = MutableStateFlow<Map<String, SessionProcessingState>>(emptyMap())

    val states: StateFlow<Map<String, SessionProcessingState>> = mutableStates.asStateFlow()

    fun isActive(sessionFolder: String): Boolean = synchronized(lock) {
        // Cancellation may still be closing files and writing checkpoints.
        jobs[sessionFolder]?.isCompleted == false
    }

    fun start(
        context: Context,
        sessionFolder: String,
        label: String,
        initialStatus: String,
        initialTotal: Int,
        block: suspend () -> Unit
    ): Job? = synchronized(lock) {
        if (jobs[sessionFolder]?.isCompleted == false) return null
        val application = context.applicationContext
        val recoveryStore = ProcessingRecoveryStore(application)
        etaEstimators[sessionFolder] = ProcessingEtaEstimator()
        val job = scope.launch(start = CoroutineStart.LAZY) {
            try {
                block()
            } catch (error: CancellationException) {
                update(sessionFolder, "Обработка остановлена")
                throw error
            } catch (error: Throwable) {
                update(sessionFolder, error.message ?: "Ошибка обработки")
            }
        }
        // Register before Main.immediate can run even an immediately completing block.
        jobs[sessionFolder] = job
        job.invokeOnCompletion {
            synchronized(lock) {
                if (jobs[sessionFolder] === job) {
                    jobs.remove(sessionFolder)
                    etaEstimators.remove(sessionFolder)
                    val previous = mutableStates.value[sessionFolder]
                    if (previous != null) updateState(previous.copy(running = false, stageEtaMillis = null))
                    runCatching { recoveryStore.markFinished(sessionFolder) }
                        .onFailure { Log.e("AstroPhotoProcessing", "Unable to finish recovery record", it) }
                    // The service stops itself after acknowledging Android's start request.
                    // Stopping it here can race startForeground() for a very short task.
                }
            }
        }
        updateState(SessionProcessingState(sessionFolder, label, initialStatus, 0, initialTotal, true))
        try {
            recoveryStore.markStarted(sessionFolder, label)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
                ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) !=
                PackageManager.PERMISSION_GRANTED
            ) {
                (context as? Activity)?.let { activity ->
                    ActivityCompat.requestPermissions(activity,
                        arrayOf(Manifest.permission.POST_NOTIFICATIONS), NOTIFICATION_PERMISSION_REQUEST)
                }
            }
            ContextCompat.startForegroundService(application,
                Intent(application, ProcessingForegroundService::class.java)
                    .setAction(ProcessingForegroundService.ACTION_START))
        } catch (error: RuntimeException) {
            Log.e("AstroPhotoProcessing", "Unable to start foreground processing", error)
            update(sessionFolder, "Не удалось начать обработку. Откройте приложение и повторите запуск.")
            job.cancel()
            return null
        }
        job.start()
        job
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
                    total = total ?: previous.total,
                    stageEtaMillis = if (current != null && total != null) {
                        etaEstimators[sessionFolder]?.update(status, current, total, SystemClock.elapsedRealtime())
                    } else null
                )
            )
        }
    }

    fun cancel(sessionFolder: String) {
        synchronized(lock) {
            jobs[sessionFolder]?.let { job ->
                if (!job.isCompleted) {
                    update(sessionFolder, "Остановка обработки…")
                    job.cancel()
                }
            }
        }
    }

    fun cancelAll() {
        synchronized(lock) { jobs.keys.toList() }.forEach(::cancel)
    }

    private fun updateState(state: SessionProcessingState) {
        mutableStates.value = mutableStates.value + (state.sessionFolder to state)
    }

    private const val NOTIFICATION_PERMISSION_REQUEST = 4102
}

class ProcessingRecoveryStore(context: Context) {
    private val preferences = context.getSharedPreferences(PREFERENCES, Context.MODE_PRIVATE)

    fun markStarted(sessionFolder: String, label: String) = synchronized(recoveryLock) {
        write(read().apply { put(sessionFolder, label) })
    }

    fun markFinished(sessionFolder: String) = synchronized(recoveryLock) {
        write(read().apply { remove(sessionFolder) })
    }

    /** Reading does not dismiss a warning: Activity recreation must not lose it. */
    fun pendingInterrupted(): List<InterruptedSessionProcessing> =
        synchronized(recoveryLock) { read().toMap() }.map { (folder, label) ->
            InterruptedSessionProcessing(folder, label)
        }.filterNot { SessionProcessingCoordinator.isActive(it.sessionFolder) }

    private fun read(): MutableMap<String, String> {
        val records = linkedMapOf<String, String>()
        preferences.getString(KEY_RECORDS, null)?.let { encoded ->
            val json = JSONObject(encoded)
            json.keys().forEach { folder -> records[folder] = json.getString(folder) }
        }
        // Preserve a task recorded by the earlier single-session format.
        preferences.getString(KEY_SESSION, null)?.let { folder ->
            records.putIfAbsent(folder, preferences.getString(KEY_LABEL, null) ?: "Обработка")
        }
        return records
    }

    private fun write(records: Map<String, String>) {
        // Persist before work starts so a killed process leaves a durable recovery record.
        check(preferences.edit().remove(KEY_SESSION).remove(KEY_LABEL)
            .putString(KEY_RECORDS, JSONObject(records).toString()).commit()) {
            "Unable to persist processing recovery record"
        }
    }

    private companion object {
        const val PREFERENCES = "processing_recovery"
        const val KEY_SESSION = "session_folder"
        const val KEY_LABEL = "label"
        const val KEY_RECORDS = "sessions"
        val recoveryLock = Any()
    }
}
