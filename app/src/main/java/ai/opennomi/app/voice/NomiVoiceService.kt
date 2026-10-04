package ai.opennomi.app.voice

import android.app.*
import android.content.*
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.PowerManager
import androidx.core.content.ContextCompat
import androidx.core.app.NotificationCompat
import ai.opennomi.app.MainActivity
import ai.opennomi.app.NomiApplication
import ai.opennomi.app.R
import ai.opennomi.app.screen.ScreenState
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.combine

/** A microphone session started by the user's visible Activity, retained across App switches. */
class NomiVoiceService : Service() {
    companion object {
        private const val START="nomi.voice.start"
        private const val STOP="nomi.voice.stop"
        fun start(context: Context, withScreen: Boolean) {
            ContextCompat.startForegroundService(context,Intent(context,NomiVoiceService::class.java).setAction(START).putExtra("screen",withScreen))
        }
        fun stop(context: Context) { context.stopService(Intent(context,NomiVoiceService::class.java)) }
        fun toggle(context: Context) {
            val model=(context.applicationContext as NomiApplication).cloudModel
            if(model.backgroundConversation.value)stop(context) else start(context,ScreenState.state.value.active)
        }
    }
    private val scope=CoroutineScope(SupervisorJob()+Dispatchers.Main.immediate)
    private val model get()=(application as NomiApplication).cloudModel
    private var started=false
    private var wake: PowerManager.WakeLock? = null
    override fun onBind(intent: Intent?)=null
    override fun onStartCommand(intent: Intent?,flags:Int,startId:Int):Int {
        if(intent?.action==STOP){stopSelf();return START_NOT_STICKY}
        if(intent?.action!=START)return START_NOT_STICKY
        try {
            getSystemService(NotificationManager::class.java).createNotificationChannel(NotificationChannel("nomi-voice","小智语音聊天",NotificationManager.IMPORTANCE_LOW))
            val stop=PendingIntent.getService(this,43,Intent(this,NomiVoiceService::class.java).setAction(STOP),PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
            val open=PendingIntent.getActivity(this,44,Intent(this,MainActivity::class.java),PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
            val notification=NotificationCompat.Builder(this,"nomi-voice").setSmallIcon(R.drawable.ic_nomi).setContentTitle("小智正在陪你聊天")
                .setContentText("麦克风已开启 · 切到其他 App 也可以说话").setContentIntent(open).setOngoing(true).addAction(0,"暂停语音",stop).build()
            if(Build.VERSION.SDK_INT>=29)startForeground(403,notification,ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE or ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK)else startForeground(403,notification)
            if(ScreenState.state.value.audio)error("请先关闭视频声音识别，再开启语音聊天")
            model.startBackgroundConversation(intent.getBooleanExtra("screen",false))
            if(!started) {
                started=true
                wake=getSystemService(PowerManager::class.java).newWakeLock(PowerManager.PARTIAL_WAKE_LOCK,"OpenNomi:Voice").apply { setReferenceCounted(false);acquire(35*60*1000L) }
                scope.launch { while(isActive) { delay(30*60*1000L);wake?.acquire(35*60*1000L) } }
                scope.launch {
                    combine(model.backgroundConversation,model.status){on,status->on to status}.collect{(on,status)->
                        ScreenState.update{it.copy(voiceOn=on,voiceStatus=status)}
                        if(!on)stopSelf()
                    }
                }
            }
        } catch(e:Exception){ScreenState.event("语音聊天未能启动：${e.message}");stopSelf()}
        return START_NOT_STICKY
    }
    override fun onDestroy() {
        scope.cancel();wake?.let { if(it.isHeld)it.release() };wake=null;model.stopBackgroundConversation()
        ScreenState.update{it.copy(voiceOn=false,voiceStatus="语音已暂停")}
        stopForeground(STOP_FOREGROUND_REMOVE);super.onDestroy()
    }
}
