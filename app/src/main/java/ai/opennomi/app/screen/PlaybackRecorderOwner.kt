package ai.opennomi.app.screen

import java.util.concurrent.atomic.AtomicBoolean

/** Native stop may block. Keep it off the UI thread and serialize it with release. */
class PlaybackRecorderOwner<T : Any>(private val resource:T,
    private val stop:(T)->Unit,private val release:(T)->Unit) {
    private val lock=Any()
    private val stopRequested=AtomicBoolean(false)
    private var stopped=false
    private var released=false

    fun start(action:(T)->Unit):Boolean = synchronized(lock) {
        if(stopRequested.get() || released)return@synchronized false
        action(resource)
        true
    }

    fun requestStop() {
        if(!stopRequested.compareAndSet(false,true))return
        Thread({synchronized(lock){stopLocked()}},"nomi-playback-stop").start()
    }

    private fun stopLocked() {
        if(stopped || released)return
        stopped=true
        runCatching{stop(resource)}
    }

    /** Called by the capture worker after its last read, never by the UI. */
    fun finish() = synchronized(lock) {
        if(released)return@synchronized
        stopRequested.set(true)
        stopLocked()
        try{release(resource)}finally{released=true}
    }
}
