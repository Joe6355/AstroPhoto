package com.joe6355.astrophoto

import kotlinx.coroutines.CancellationException

/** One owner per folder, including cleanup after cancellation. Different sessions are independent. */
internal class SessionAccessRegistry {
    private val owners = mutableMapOf<String, Any>()

    @Synchronized fun acquire(folder: String): AutoCloseable? {
        if (folder in owners) return null
        val token = Any()
        owners[folder] = token
        return AutoCloseable {
            synchronized(this) {
                if (owners[folder] === token) owners.remove(folder)
            }
        }
    }
}

internal val sessionAccess = SessionAccessRegistry()

internal suspend fun <T> guardedSessionOperation(folder: String, block: suspend () -> T): Result<T> {
    val access = sessionAccess.acquire(folder) ?: return Result.failure(IllegalStateException(
        "Сессия занята. Дождитесь завершения обработки или текущей файловой операции."
    ))
    return try {
        Result.success(block())
    } catch (error: CancellationException) {
        throw error
    } catch (error: Throwable) {
        Result.failure(error)
    } finally {
        access.close()
    }
}
