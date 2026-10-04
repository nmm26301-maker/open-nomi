package ai.opennomi.app.voice

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeout

/** One observer for the whole voice session, including consecutive on/off requests. */
class AcknowledgedSwitch(private val request: (Boolean) -> Unit) {
    private val observed = MutableStateFlow<Boolean?>(null)
    private val lock = Mutex()
    fun observed(enabled: Boolean) { observed.value = enabled }
    fun unavailable() { observed.value = null }
    suspend fun set(enabled: Boolean, timeoutMillis: Long = 4000) = lock.withLock {
        if (observed.value == enabled) return@withLock
        request(enabled)
        withTimeout(timeoutMillis) { observed.filterNotNull().first { it == enabled } }
    }
}
