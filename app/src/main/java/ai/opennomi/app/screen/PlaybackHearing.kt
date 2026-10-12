package ai.opennomi.app.screen

import android.content.Context
import android.content.pm.PackageManager
import android.media.*
import android.media.projection.MediaProjection
import android.os.Build
import android.os.SystemClock
import androidx.core.content.ContextCompat
import org.vosk.Model
import org.vosk.Recognizer
import org.json.JSONObject
import java.io.File
import java.util.concurrent.atomic.AtomicBoolean

/** Processes captured player PCM; partial hypotheses are previews, not settled translations. */
class PlaybackHearing(private val context:Context,private val projection:MediaProjection,
    private val modelRoot:File,private val result:(String,Double?)->Unit,
    private val partial:(String)->Unit,private val error:(String)->Unit,private val stopped:()->Unit,private val ready:()->Unit) {
    private val running=AtomicBoolean(false)
    @Volatile private var recorder:AudioRecord?=null
    fun start() {
        check(Build.VERSION.SDK_INT>=29){"视频声音字幕需要 Android 10 以上"}
        check(ContextCompat.checkSelfPermission(context,android.Manifest.permission.RECORD_AUDIO)==PackageManager.PERMISSION_GRANTED){"请允许音频录制权限"}
        check(running.compareAndSet(false,true))
        Thread({
            var model:Model?=null;var recognizer:Recognizer?=null
            try {
                if(Build.VERSION.SDK_INT<29)throw IllegalStateException("视频声音字幕需要 Android 10 以上")
                model=Model(modelRoot.absolutePath);recognizer=Recognizer(model,16000f)
                recognizer.setWords(true)
                if(!running.get())return@Thread
                val min=AudioRecord.getMinBufferSize(16000,AudioFormat.CHANNEL_IN_MONO,AudioFormat.ENCODING_PCM_16BIT)
                check(min>0){"系统不支持视频声音采样格式"}
                val config=AudioPlaybackCaptureConfiguration.Builder(projection)
                    .addMatchingUsage(AudioAttributes.USAGE_MEDIA).addMatchingUsage(AudioAttributes.USAGE_GAME)
                    .addMatchingUsage(AudioAttributes.USAGE_UNKNOWN).excludeUid(android.os.Process.myUid()).build()
                val capture=AudioRecord.Builder().setAudioFormat(AudioFormat.Builder()
                    .setEncoding(AudioFormat.ENCODING_PCM_16BIT).setSampleRate(16000).setChannelMask(AudioFormat.CHANNEL_IN_MONO).build())
                    .setBufferSizeInBytes(maxOf(min*2,16000)).setAudioPlaybackCaptureConfig(config).build()
                check(capture.state==AudioRecord.STATE_INITIALIZED){"系统未能建立声音录制"}
                recorder=capture
                if(!running.get())return@Thread
                capture.startRecording();ready()
                val buffer=ByteArray(3200);val phrase=AudioPhraseClock()
                var quietSince=SystemClock.elapsedRealtime();var speechSince=quietSince
                var lastPreview="";var lastPreviewAt=0L
                fun emit(raw:String) {
                    val json=JSONObject(raw);val text=json.optString("text").trim()
                    if(text.isNotBlank() && running.get()) {
                        val words=json.optJSONArray("result")
                        val confidence=if(words!=null && words.length()>0)(0 until words.length()).map{words.getJSONObject(it).optDouble("conf",1.0)}.average() else null
                        result(text,confidence);speechSince=SystemClock.elapsedRealtime()
                    }
                    phrase.reset();lastPreview="";partial("")
                }
                while(running.get()) {
                    val n=capture.read(buffer,0,buffer.size)
                    if(n<0){if(running.get())error("视频声音读取中断，请重新开启字幕");break}
                    if(n==0)continue
                    val now=SystemClock.elapsedRealtime();var energy=0L
                    for(i in 0 until n-1 step 2){val sample=((buffer[i+1].toInt() shl 8) or (buffer[i].toInt() and 255)).toShort().toInt();energy+=kotlin.math.abs(sample)}
                    if(energy>n*8)quietSince=now
                    if(now-quietSince>15000){error("未捕获到视频原声：请播放视频并提高媒体音量；该播放应用也可能禁止声音捕获");quietSince=now}
                    if(recognizer.acceptWaveForm(buffer,n))emit(recognizer.result)
                    else {
                        val text=JSONObject(recognizer.partialResult).optString("partial").trim()
                        if(text!=lastPreview && now-lastPreviewAt>=400){partial(text);lastPreview=text;lastPreviewAt=now}
                        if(phrase.update(text.isNotBlank(),now)){emit(recognizer.finalResult);recognizer.reset()}
                    }
                    if(now-speechSince>25000 && now-quietSince<1000){error("已收到声音，但没有识别出完整语句；请核对原声语言，背景音乐或多人重叠说话会影响识别");speechSince=now}
                }
            }catch(t:Throwable){if(running.get())error("视频声音识别未完成：${t.message ?: "请重新开启字幕"}")}
            finally {
                running.set(false);runCatching{recorder?.stop()};recorder?.release();recorder=null
                recognizer?.close();model?.close();stopped()
            }
        },"nomi-playback-vosk").start()
    }
    fun close(){running.set(false);runCatching{recorder?.stop()}}
}
