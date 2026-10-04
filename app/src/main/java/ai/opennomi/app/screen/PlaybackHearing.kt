package ai.opennomi.app.screen

import android.content.Context
import android.media.*
import android.media.projection.MediaProjection
import android.os.Build
import org.vosk.Model
import org.vosk.Recognizer
import org.json.JSONObject
import java.io.File
import java.util.concurrent.atomic.AtomicBoolean

/** Vosk processes captured PCM directly: this does not listen through the microphone. */
class PlaybackHearing(private val context:Context,private val projection:MediaProjection,private val result:(String)->Unit,private val error:(String)->Unit) {
    private val running=AtomicBoolean(false);@Volatile private var recorder:AudioRecord?=null
    fun start() {
        check(Build.VERSION.SDK_INT>=29){"需要 Android 10 以上"};check(running.compareAndSet(false,true))
        Thread({
            var model:Model?=null;var recognizer:Recognizer?=null
            try {
                val root=File(context.filesDir,"playback-en")
                if(!File(root,"ready").exists()) {
                    fun copy(path:String,rel:String) { val children=context.assets.list(path).orEmpty();if(children.isEmpty()){val f=File(root,rel);f.parentFile?.mkdirs();context.assets.open(path).use{input->f.outputStream().use{input.copyTo(it)}}} else children.forEach { child->copy("$path/$child",if(rel.isEmpty())child else "$rel/$child") } }
                    copy("playback-model","");File(root,"ready").writeText("1")
                }
                if(!running.get())return@Thread
                model=Model(root.absolutePath);recognizer=Recognizer(model,16000f)
                val min=AudioRecord.getMinBufferSize(16000,AudioFormat.CHANNEL_IN_MONO,AudioFormat.ENCODING_PCM_16BIT);check(min>0)
                if(Build.VERSION.SDK_INT < 29) error("需要 Android 10 以上")
                if(androidx.core.content.ContextCompat.checkSelfPermission(context,android.Manifest.permission.RECORD_AUDIO) != android.content.pm.PackageManager.PERMISSION_GRANTED) error("请允许音频录制权限")
                val config=AudioPlaybackCaptureConfiguration.Builder(projection).addMatchingUsage(AudioAttributes.USAGE_MEDIA).addMatchingUsage(AudioAttributes.USAGE_GAME).excludeUid(android.os.Process.myUid()).build()
                val capture=AudioRecord.Builder().setAudioFormat(AudioFormat.Builder().setEncoding(AudioFormat.ENCODING_PCM_16BIT).setSampleRate(16000).setChannelMask(AudioFormat.CHANNEL_IN_MONO).build()).setBufferSizeInBytes(maxOf(min*2,16000)).setAudioPlaybackCaptureConfig(config).build()
                check(capture.state==AudioRecord.STATE_INITIALIZED){"系统未能建立声音录制"};recorder=capture
                if(!running.get())return@Thread
                capture.startRecording();val buffer=ByteArray(3200);var quietSince=System.currentTimeMillis();var lastPartial="";var lastEmit=0L
                while(running.get()) {
                    val n=capture.read(buffer,0,buffer.size);if(n<0){if(running.get())error("声音读取中断，请重新启动");break};if(n==0)continue
                    val now=System.currentTimeMillis();var energy=0L;for(i in 0 until n-1 step 2){val sample=((buffer[i+1].toInt() shl 8) or (buffer[i].toInt() and 255)).toShort().toInt();energy+=kotlin.math.abs(sample)}
                    if(energy>n*8)quietSince=now
                    if(now-quietSince>14000){error("未捕获视频声音：播放应用可能禁止录音，可切回屏幕字幕翻译");break}
                    val final=recognizer.acceptWaveForm(buffer,n);val text=JSONObject(if(final)recognizer.result else recognizer.partialResult).optString(if(final)"text" else "partial")
                    if(text.isNotBlank() && text!=lastPartial && (final || now-lastEmit>6000)){result(text);lastPartial=text;lastEmit=now}
                }
            }catch(t:Throwable){if(running.get())error("视频声音识别未完成：${t.message}")}
            finally{running.set(false);runCatching{recorder?.stop()};recorder?.release();recorder=null;recognizer?.close();model?.close()}
        },"nomi-playback-vosk").start()
    }
    fun close(){running.set(false);runCatching{recorder?.stop()}}
}
