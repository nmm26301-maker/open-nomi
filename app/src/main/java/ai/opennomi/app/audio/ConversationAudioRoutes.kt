package ai.opennomi.app.audio

import android.Manifest
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothHeadset
import android.bluetooth.BluetoothProfile
import android.content.*
import android.content.pm.PackageManager
import android.media.*
import android.os.*
import androidx.annotation.RequiresApi
import androidx.core.content.ContextCompat
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*

/** App-wide routing lease. Pausing a microphone never tears down a conversation's headset. */
class ConversationAudioRoutes(private val context: Context) {
    private val main = Handler(Looper.getMainLooper())
    private val audio = context.getSystemService(AudioManager::class.java)
    private val _state = MutableStateFlow(ConversationRoute())
    val state = _state.asStateFlow()
    @Volatile var active = false; private set
    private var originalMode = AudioManager.MODE_NORMAL
    private var originalSpeaker = false
    private var legacySco = false
    private var watching = false
    private var modernWatcher: Any? = null
    private val platform = object : ConversationRoutePlatform {
        override fun devices() = available()
        override fun current() = actual()
        override fun enter() {
            originalMode = audio.mode
            @Suppress("DEPRECATION")
            originalSpeaker = audio.isSpeakerphoneOn
            active = true
            audio.mode = AudioManager.MODE_IN_COMMUNICATION
            watch()
        }
        override fun select(device: ConversationDevice): Boolean = runCatching {
            if (Build.VERSION.SDK_INT >= 31) {
                val target = audio.availableCommunicationDevices.firstOrNull { it.id == device.id } ?: return@runCatching false
                audio.setCommunicationDevice(target)
            } else {
                @Suppress("DEPRECATION")
                if (device.kind == RouteKind.BLUETOOTH) {
                    audio.isSpeakerphoneOn = false
                    audio.startBluetoothSco()
                    audio.isBluetoothScoOn = true
                } else {
                    audio.isBluetoothScoOn = false; audio.stopBluetoothSco(); legacySco = false
                    audio.isSpeakerphoneOn = device.kind == RouteKind.SPEAKER
                }
                true
            }
        }.getOrDefault(false)
        override fun exit() {
            active = false; unwatch()
            runCatching {
                if (Build.VERSION.SDK_INT >= 31) audio.clearCommunicationDevice()
                else { @Suppress("DEPRECATION") audio.isBluetoothScoOn = false; audio.stopBluetoothSco() }
                @Suppress("DEPRECATION")
                audio.isSpeakerphoneOn = originalSpeaker
                audio.mode = originalMode
            }
            legacySco = false
        }
    }
    private val controller: ConversationRouteController = ConversationRouteController(platform, {
        _state.value = if(it.active && !hasBluetoothPermission()) it.copy(message = it.message + " · 附近设备未授权") else it
    }) { request -> scheduleTimeout(request) }
    private fun scheduleTimeout(request: Long) { main.postDelayed({ controller.expired(request) }, 8000) }
    class Lease internal constructor(private val routes: ConversationAudioRoutes, private val owner: Any) {
        private var closed = false
        fun close() { if (!closed) { closed = true; routes.controller.release(owner) } }
    }
    fun acquire(): Lease {
        check(Looper.myLooper() == Looper.getMainLooper())
        val owner = Any(); controller.acquire(owner); return Lease(this, owner)
    }
    suspend fun awaitReady() {
        val ready = withTimeout(18000) { state.first { !it.active || !it.pending } }
        check(ready.active && ready.device != null) { ready.message }
    }
    fun refresh() { controller.devicesChanged() }
    fun hasBluetoothPermission() = Build.VERSION.SDK_INT < 31 || ContextCompat.checkSelfPermission(context,
        Manifest.permission.BLUETOOTH_CONNECT) == PackageManager.PERMISSION_GRANTED
    private fun device(info: AudioDeviceInfo): ConversationDevice? {
        val kind = when (info.type) {
            AudioDeviceInfo.TYPE_BLUETOOTH_SCO, AudioDeviceInfo.TYPE_BLE_HEADSET -> RouteKind.BLUETOOTH
            AudioDeviceInfo.TYPE_WIRED_HEADSET, AudioDeviceInfo.TYPE_USB_HEADSET -> RouteKind.WIRED
            AudioDeviceInfo.TYPE_BUILTIN_SPEAKER -> RouteKind.SPEAKER
            AudioDeviceInfo.TYPE_BUILTIN_EARPIECE -> RouteKind.EARPIECE
            else -> return null
        }
        if (kind == RouteKind.BLUETOOTH && !hasBluetoothPermission()) return null
        return ConversationDevice(info.id, kind, info.productName.toString())
    }
    private fun available(): List<ConversationDevice> = runCatching {
        if (Build.VERSION.SDK_INT >= 31) audio.availableCommunicationDevices.mapNotNull(::device)
        else {
            val outputs = audio.getDevices(AudioManager.GET_DEVICES_OUTPUTS).mapNotNull(::device).filter { it.kind != RouteKind.BLUETOOTH }
            @Suppress("DEPRECATION")
            val connected = try {
                BluetoothAdapter.getDefaultAdapter()?.getProfileConnectionState(BluetoothProfile.HEADSET) == BluetoothAdapter.STATE_CONNECTED
            } catch (_: SecurityException) { false }
            if (connected) outputs + ConversationDevice(-1, RouteKind.BLUETOOTH, "蓝牙耳机") else outputs
        }
    }.getOrDefault(emptyList())
    private fun actual(): ConversationDevice? = runCatching {
        if (Build.VERSION.SDK_INT >= 31) audio.communicationDevice?.let(::device)
        else if (legacySco) ConversationDevice(-1, RouteKind.BLUETOOTH, "蓝牙耳机")
        else {
            @Suppress("DEPRECATION")
            val speaker = audio.isSpeakerphoneOn
            available().firstOrNull { if (speaker) it.kind == RouteKind.SPEAKER else it.kind == RouteKind.WIRED }
        }
    }.getOrNull()
    private val devices = object : AudioDeviceCallback() {
        override fun onAudioDevicesAdded(added: Array<out AudioDeviceInfo>) { controller.devicesChanged() }
        override fun onAudioDevicesRemoved(removed: Array<out AudioDeviceInfo>) { controller.devicesChanged() }
    }
    private val sco = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (!active) return
            if (intent?.action == AudioManager.ACTION_SCO_AUDIO_STATE_UPDATED) {
                legacySco = intent.getIntExtra(AudioManager.EXTRA_SCO_AUDIO_STATE, -1) == AudioManager.SCO_AUDIO_STATE_CONNECTED
                controller.confirmed(actual())
            } else controller.devicesChanged()
        }
    }
    private fun watch() {
        if (watching) return
        watching = true
        audio.registerAudioDeviceCallback(devices, main)
        if (Build.VERSION.SDK_INT >= 31) watchModern()
        else {
            val sticky = ContextCompat.registerReceiver(context, sco, IntentFilter(AudioManager.ACTION_SCO_AUDIO_STATE_UPDATED).apply {
                addAction(BluetoothHeadset.ACTION_CONNECTION_STATE_CHANGED)
            }, ContextCompat.RECEIVER_EXPORTED)
            legacySco = sticky?.getIntExtra(AudioManager.EXTRA_SCO_AUDIO_STATE, -1) == AudioManager.SCO_AUDIO_STATE_CONNECTED
        }
    }
    @RequiresApi(31) private fun watchModern() {
        val listener = AudioManager.OnCommunicationDeviceChangedListener { _ -> controller.confirmed(actual()) }
        modernWatcher = listener; audio.addOnCommunicationDeviceChangedListener(context.mainExecutor, listener)
    }
    private fun unwatch() {
        if (!watching) return
        watching = false; audio.unregisterAudioDeviceCallback(devices)
        if (Build.VERSION.SDK_INT >= 31) unwatchModern() else runCatching { context.unregisterReceiver(sco) }
    }
    @RequiresApi(31) private fun unwatchModern() {
        (modernWatcher as? AudioManager.OnCommunicationDeviceChangedListener)?.let(audio::removeOnCommunicationDeviceChangedListener)
        modernWatcher = null
    }
}
